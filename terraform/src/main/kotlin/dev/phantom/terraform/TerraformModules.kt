// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentsOfType

/**
 * A Terraform module is the set of .tf files in one directory; every file sees the declarations of the others. Only the
 * files themselves are read: nothing from `.terraform`, the registry or a provider.
 */
object TerraformModules {
  /** Address roots whose second name is not a resource name. */
  private val NOT_RESOURCES = setOf("var", "local", "module", "data", "ephemeral", "path", "terraform", "count", "each", "self")

  /** Declarations by address: `var.x`, `local.x`, `module.x`, `data.t.n`, `ephemeral.t.n`, `t.n` for resources. */
  fun declarations(file: HclFile): Map<String, PsiElement> = CachedValuesManager.getCachedValue(file) {
    val result = LinkedHashMap<String, PsiElement>()
    for (f in moduleFiles(file)) {
      for (block in f.blocks) collect(block, result)
    }
    CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
  }

  private fun collect(block: HclBlock, result: MutableMap<String, PsiElement>) {
    when (block.type) {
      "locals" -> block.attributes.forEach { a -> a.name?.let { result.putIfAbsent("local.$it", a) } }
      // A check block may hold its own scoped data source.
      "check" -> block.blocks.forEach { collect(it, result) }
      else -> address(block)?.let { result.putIfAbsent(it, block) }
    }
  }

  private fun address(block: HclBlock): String? {
    val labels = block.labels.map { it.value }
    val prefix = when (block.type) {
      "variable" -> "var"
      "module" -> "module"
      "resource" -> null
      "data", "ephemeral" -> block.type
      else -> return null
    }
    val expected = if (block.type == "variable" || block.type == "module") 1 else 2
    if (labels.size != expected) return null
    return (listOfNotNull(prefix) + labels).joinToString(".")
  }

  /** The other .tf files of the directory, or only this file when it is not saved in one. */
  private fun moduleFiles(file: HclFile): List<HclFile> {
    val dir = file.originalFile.virtualFile?.parent ?: return listOf(file)
    return listOf(file) + filesIn(file, dir).filter { it != file && it != file.originalFile }
  }

  private fun filesIn(context: HclFile, dir: VirtualFile): List<HclFile> {
    val manager = PsiManager.getInstance(context.project)
    return dir.children.filter { !it.isDirectory && it.name.endsWith(".tf") }.mapNotNull { manager.findFile(it) as? HclFile }
  }

  /** The number of names in an address before attribute names start: `var.x` 2, `data.t.n` 3, `module.m.out` 3. */
  fun declarationLength(chain: List<String>): Int = when (chain[0]) {
    "data", "ephemeral" -> 3
    else -> 2
  }

  fun isResourceAddress(chain: List<String>): Boolean = chain[0] !in NOT_RESOURCES

  /**
   * The declaration an address refers to. Names after it resolve to the attribute of that name set in the declaring
   * block, or for a module to the output of the module's local source directory. Attributes that only the provider
   * computes (such as `id`) are not in the configuration and do not resolve.
   */
  fun resolve(file: HclFile, chain: List<String>): PsiElement? {
    val root = chain[0]
    if (root in setOf("path", "terraform", "count", "each", "self")) return null
    val length = declarationLength(chain)
    if (chain.size < length) return null
    val declaration = declarations(file)[chain.take(length).joinToString(".")] ?: return null
    return when {
      chain.size == length -> declaration
      chain.size == length + 1 && root == "module" -> moduleOutput(declaration as HclBlock, chain[length])
      chain.size == length + 1 && root != "var" && root != "local" -> (declaration as? HclBlock)?.attribute(chain[length])
      else -> null
    }
  }

  /** The `output` block of a module whose `source` is a local path (`./` or `../`), or null when it is not local. */
  fun moduleOutput(module: HclBlock, name: String): PsiElement? = localModuleFiles(module)?.let { files ->
    files.asSequence().flatMap { it.blocks }.firstOrNull { it.type == "output" && it.name == name }
  }

  /** The .tf files of a module's local source directory; null for a registry, git or missing source. */
  fun localModuleFiles(module: HclBlock): List<HclFile>? {
    val source = (module.attribute("source")?.value?.text ?: return null).removeSurrounding("\"")
    if (!source.startsWith("./") && !source.startsWith("../")) return null
    val dir = module.containingFile.originalFile.virtualFile?.parent?.findFileByRelativePath(source)?.takeIf { it.isDirectory } ?: return null
    return filesIn(module.containingFile as HclFile, dir)
  }
}

object TerraformScopes {
  /**
   * True when the root of the address is a name the expression declares itself: a for expression or `%{for}`
   * variable, or the iterator of an enclosing `dynamic` block (its label, or its `iterator` attribute).
   */
  fun isLocalName(element: HclGetAttr): Boolean {
    val root = PsiTreeUtil.getDeepestFirst(element).text
    for (parent in element.parentsOfType<PsiElement>(withSelf = false)) {
      when (parent) {
        is HclBlock -> if (parent.type == "dynamic") {
          val iterator = parent.attribute("iterator")?.value?.text ?: parent.labels.firstOrNull()?.value
          if (iterator == root) return true
        }
        else -> if (parent.node.elementType == HclElements.FOR_EXPR || parent.node.elementType == HclElements.TEMPLATE) {
          if (PsiTreeUtil.findChildrenOfType(parent, HclForVar::class.java).any { it.name == root }) return true
        }
      }
    }
    return false
  }
}

/**
 * Reports `var.`, `local.`, `module.`, `data.` and resource addresses with no declaration in the module, and outputs a
 * local module does not declare. Terraform rejects all of them at plan time.
 */
class TerraformUnresolvedReferenceInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? HclFile
    if (file == null || !file.isConfiguration) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (element is HclGetAttr && isUnresolved(element)) {
          val reference = element.reference ?: return
          holder.registerProblem(
            element,
            reference.rangeInElement,
            TerraformBundle.message("inspection.unresolved", element.chain.orEmpty().joinToString(".")),
          )
        }
      }
    }
  }

  private fun isUnresolved(element: HclGetAttr): Boolean {
    val chain = element.chain ?: return false
    if (element.parentsOfType<HclBlock>().any { it.type == "moved" || it.type == "removed" }) return false
    val length = TerraformModules.declarationLength(chain)
    val checked = when {
      chain[0] in setOf("var", "local", "module", "data", "ephemeral") -> chain.size == length
      TerraformModules.isResourceAddress(chain) -> chain.size == 2 && '_' in chain[0]
      else -> false
    }
    if (checked) return !TerraformScopes.isLocalName(element) && element.resolve() == null
    // module.name.output: only for a local module that exists, since a remote one is not read.
    if (chain[0] == "module" && chain.size == 3) {
      val module = TerraformModules.declarations(element.containingFile as HclFile)["module.${chain[1]}"] as? HclBlock ?: return false
      return TerraformModules.localModuleFiles(module) != null && element.resolve() == null
    }
    return false
  }
}
