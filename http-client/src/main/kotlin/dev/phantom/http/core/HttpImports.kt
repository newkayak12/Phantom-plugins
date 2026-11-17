// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.nio.file.Path

/** Path text of an `import` or `run` line resolved against the `.http` file's directory (absolute paths as they are). */
internal fun resolveHttpPath(element: PsiElement, path: String): VirtualFile? {
  if (path.isEmpty()) return null
  if (runCatching { Path.of(path).isAbsolute }.getOrDefault(false)) return LocalFileSystem.getInstance().findFileByPath(path)
  val dir = element.containingFile.originalFile.virtualFile?.parent ?: return null
  return dir.findFileByRelativePath(path.removePrefix("./"))?.takeIf { !it.isDirectory }
}

private fun fileReferences(element: PsiElement, token: ASTNode?): Array<PsiReference> {
  token ?: return PsiReference.EMPTY_ARRAY
  val text = token.text
  if (text.isEmpty()) return PsiReference.EMPTY_ARRAY
  return FileReferenceSet(text, element, token.startOffsetInParent, null, true).allReferences.map { it as PsiReference }.toTypedArray()
}

/** `import ./other.http`: its named requests can be run with `run #name`, its file variables resolve here. */
class HttpImport(node: ASTNode) : HttpElement(node) {
  private val pathNode: ASTNode? get() = node.findChildByType(HttpTokens.IMPORT_PATH)
  val path: String get() = pathNode?.text?.trim().orEmpty()
  val pathRange: TextRange? get() = pathNode?.let { TextRange.from(it.startOffsetInParent, it.textLength) }
  val importedFile: VirtualFile? get() = resolveHttpPath(this, path)

  override fun getReferences(): Array<PsiReference> = fileReferences(this, pathNode)
}

/** `run #Login (@user=admin)` runs one named request, `run ./other.http (@host=x)` every request of a file. */
class HttpRun(node: ASTNode) : HttpElement(node) {
  private val nameNode: ASTNode? get() = node.findChildByType(HttpTokens.RUN_REQUEST_NAME)
  private val pathNode: ASTNode? get() = node.findChildByType(HttpTokens.RUN_PATH)

  /** The request name after `#`, or null when the line runs a file. */
  val requestName: String? get() = nameNode?.text?.removePrefix("#")?.trim()
  val path: String? get() = pathNode?.text?.trim()
  val targetFile: VirtualFile? get() = path?.let { resolveHttpPath(this, it) }

  /** The text range of the name (without `#`) or of the path. */
  val targetRange: TextRange?
    get() = (nameNode ?: pathNode)?.let {
      val skip = if (it === nameNode) 1 else 0
      TextRange(it.startOffsetInParent + skip, it.startOffsetInParent + it.textLength)
    }

  /** `(@host=example.com, @user=admin)` → host, user. Values are kept raw; they may hold `{{...}}` themselves. */
  val overrides: Map<String, String>
    get() {
      val text = node.findChildByType(HttpTokens.RUN_VARIABLES)?.text ?: return emptyMap()
      return OVERRIDE.findAll(text.removePrefix("(").removeSuffix(")")).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

  override fun getReferences(): Array<PsiReference> {
    val name = nameNode
    if (name != null) {
      return arrayOf(RequestNameReference(this, TextRange(name.startOffsetInParent + 1, name.startOffsetInParent + name.textLength)))
    }
    return fileReferences(this, pathNode)
  }

  private class RequestNameReference(element: HttpRun, range: TextRange) : PsiReferenceBase<HttpRun>(element, range, false) {
    override fun resolve(): PsiElement? = element.requestName?.let { HttpImports.findRequest(element.containingFile as? HttpRequestFile, it) }

    override fun getVariants(): Array<Any> {
      val file = element.containingFile as? HttpRequestFile ?: return emptyArray()
      return HttpImports.namedRequests(file).map { (name, block) ->
        LookupElementBuilder.create(name).withTypeText(block.containingFile.name, true)
          .withTailText(block.requestLine?.let { " ${it.method} ${it.target?.text.orEmpty()}" }.orEmpty(), true)
      }.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement = element
  }

  private companion object {
    val OVERRIDE = Regex("""@([\w.$-]+)\s*=\s*([^,]*)""")
  }
}

/** What `import` and `run` lines make available. Lookups follow imports of imports, each file once. */
object HttpImports {
  private const val MAX_RUN_DEPTH = 10

  /** Files imported by [file], directly or through the files it imports, in import order. */
  fun importedFiles(file: HttpRequestFile): List<HttpRequestFile> = CachedValuesManager.getCachedValue(file) {
    val result = LinkedHashSet<HttpRequestFile>()
    val queue = ArrayDeque(listOf(file))
    val manager = PsiManager.getInstance(file.project)
    while (queue.isNotEmpty()) {
      for (import in queue.removeFirst().imports) {
        val imported = import.importedFile?.let(manager::findFile) as? HttpRequestFile ?: continue
        if (imported != file && result.add(imported)) queue += imported
      }
    }
    CachedValueProvider.Result.create(result.toList(), PsiModificationTracker.MODIFICATION_COUNT)
  }

  /** Requests `run #name` can name: this file's first, then the imported files', the first one of a name wins. */
  fun namedRequests(file: HttpRequestFile): Map<String, HttpRequestBlock> {
    val result = LinkedHashMap<String, HttpRequestBlock>()
    for (f in listOf(file) + importedFiles(file)) {
      for (block in f.requests) block.requestName?.let { result.putIfAbsent(it, block) }
    }
    return result
  }

  fun findRequest(file: HttpRequestFile?, name: String): HttpRequestBlock? = file?.let { namedRequests(it)[name] }

  /** A request and the variable values a `run` line gives it. */
  class Step(val block: HttpRequestBlock, val overrides: Map<String, String>)

  /** The requests of [file] in order, with `run` lines replaced by what they run. */
  fun steps(file: HttpRequestFile): List<Step> = steps(file, emptyMap(), 0)

  /** What one `run` line runs. Unknown names and missing files give nothing (the inspections report them). */
  fun steps(run: HttpRun): List<Step> = expand(run, emptyMap(), 0)

  private fun steps(file: HttpRequestFile, overrides: Map<String, String>, depth: Int): List<Step> {
    val result = ArrayList<Step>()
    for (block in file.blocks) {
      if (block.requestLine != null) result += Step(block, overrides)
      for (run in block.runs) result += expand(run, overrides, depth)
    }
    return result
  }

  private fun expand(run: HttpRun, outer: Map<String, String>, depth: Int): List<Step> {
    if (depth >= MAX_RUN_DEPTH) return emptyList()
    // the line's own values are the more specific ones
    val overrides = outer + run.overrides
    run.requestName?.let { name -> return listOfNotNull(findRequest(run.containingFile as? HttpRequestFile, name)?.let { Step(it, overrides) }) }
    val target = run.targetFile?.let { PsiManager.getInstance(run.project).findFile(it) } as? HttpRequestFile ?: return emptyList()
    if (target == run.containingFile) return emptyList()
    return steps(target, overrides, depth + 1)
  }
}
