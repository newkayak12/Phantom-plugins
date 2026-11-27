// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import javax.swing.Icon

/** A declaration with a name: message, enum, enum value, service, rpc, field, map field or oneof. */
open class ProtoNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  override fun getNameIdentifier(): PsiElement? = node.findChildByType(ProtoTokens.NAME)?.psi

  override fun getName(): String? = nameIdentifier?.text

  override fun setName(name: String): PsiElement {
    val old = node.findChildByType(ProtoTokens.NAME) ?: throw IncorrectOperationException()
    val dummy = ProtoElementFactory.file(project, "message $name {}")
    val new = dummy.definitions.firstOrNull()?.node?.findChildByType(ProtoTokens.NAME) ?: throw IncorrectOperationException(name)
    node.replaceChild(old, new)
    return this
  }

  override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

  val icon: Icon
    get() = when (node.elementType) {
      ProtoElements.MESSAGE -> AllIcons.Nodes.Class
      ProtoElements.ENUM -> AllIcons.Nodes.Enum
      ProtoElements.SERVICE -> AllIcons.Nodes.Interface
      ProtoElements.RPC -> AllIcons.Nodes.Method
      ProtoElements.ONEOF -> AllIcons.Nodes.Static
      else -> AllIcons.Nodes.Field
    }

  override fun getPresentation(): ItemPresentation = object : ItemPresentation {
    override fun getPresentableText(): String? = name
    override fun getLocationString(): String? = (containingFile as? ProtoFile)?.name
    override fun getIcon(unused: Boolean): Icon = icon
  }

  override fun getIcon(flags: Int): Icon = icon

  /** The named declarations directly inside this one, in file order (nested types, fields, oneofs, values, rpcs). */
  val members: List<ProtoNamedElement> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ProtoNamedElement::class.java)
}

/** A message or enum: something a field type or rpc type can refer to. */
class ProtoTypeDefinition(node: ASTNode) : ProtoNamedElement(node) {
  /** The full name protoc gives it: package, enclosing messages, own name. */
  val qualifiedName: String
    get() {
      val outer = PsiTreeUtil.getParentOfType(this, ProtoTypeDefinition::class.java)
      val prefix = outer?.qualifiedName ?: (containingFile as? ProtoFile)?.packageName.orEmpty()
      return if (prefix.isEmpty()) name.orEmpty() else "$prefix.${name.orEmpty()}"
    }
}

class ProtoPackage(node: ASTNode) : ASTWrapperPsiElement(node) {
  val packageName: String
    get() = node.getChildren(null).filter { it.elementType in ProtoTokens.WORDS || it.elementType == ProtoTokens.DOT }
      .drop(1).joinToString("") { it.text }
}

class ProtoImport(node: ASTNode) : ASTWrapperPsiElement(node) {
  private val pathToken: ASTNode? get() = node.findChildByType(ProtoTokens.STRING)

  val path: String? get() = pathToken?.text?.let { unquote(it) }

  fun resolveFile(): ProtoFile? {
    val path = path ?: return null
    val file = ProtoImports.resolve(containingFile.originalFile, path) ?: return null
    return manager.findFile(file) as? ProtoFile
  }

  override fun getReference(): PsiReference? {
    val token = pathToken ?: return null
    if (token.textLength < 2) return null
    val start = token.startOffsetInParent + 1
    return ImportReference(this, TextRange(start, start + unquote(token.text).length))
  }

  override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

  private class ImportReference(element: ProtoImport, range: TextRange) : PsiReferenceBase<ProtoImport>(element, range, true) {
    override fun resolve(): PsiElement? = element.resolveFile()

    override fun handleElementRename(newElementName: String): PsiElement {
      val path = element.path ?: return element
      val newPath = if ('/' in path) path.substringBeforeLast('/') + "/" + newElementName else newElementName
      val dummy = ProtoElementFactory.file(element.project, "import \"$newPath\";")
      return element.replace(dummy.imports.first())
    }
  }

  private companion object {
    fun unquote(text: String) = text.trim('"', '\'')
  }
}

/** A message or enum name used as a field, map value, rpc or extend type. */
class ProtoTypeRef(node: ASTNode) : ASTWrapperPsiElement(node) {
  val referenceName: String get() = text.filterNot { it.isWhitespace() }

  override fun getReference(): PsiReference = TypeReference(this)

  override fun getReferences(): Array<PsiReference> = arrayOf(reference)

  /** Resolves like protoc: the innermost enclosing scope first, then each outer scope, then the package root. */
  fun resolve(): ProtoTypeDefinition? {
    val file = containingFile as? ProtoFile ?: return null
    val types = ProtoImports.visibleTypes(file)
    val name = referenceName
    if (name.startsWith(".")) return types[name.drop(1)]
    val scope = PsiTreeUtil.getParentOfType(this, ProtoTypeDefinition::class.java)?.qualifiedName ?: file.packageName
    val parts = scope.split('.').filter { it.isNotEmpty() }
    for (i in parts.size downTo 0) {
      types[(parts.take(i) + name).joinToString(".")]?.let { return it }
    }
    return null
  }

  private class TypeReference(element: ProtoTypeRef) : PsiReferenceBase<ProtoTypeRef>(element, TextRange(0, element.textLength)) {
    override fun resolve(): PsiElement? = element.resolve()

    override fun handleElementRename(newElementName: String): PsiElement {
      val old = element.referenceName
      val newText = if ('.' in old) old.substringBeforeLast('.') + "." + newElementName else newElementName
      val dummy = ProtoElementFactory.file(element.project, "message D { $newText f = 1; }")
      val newRef = PsiTreeUtil.findChildOfType(dummy, ProtoTypeRef::class.java) ?: return element
      return element.replace(newRef)
    }
  }
}

object ProtoImports {
  /**
   * Finds an imported file the way protoc's include path would: next to the importing file first, then any file in the
   * project or its libraries whose path ends with the import path (the import root is not configured anywhere the IDE
   * can read). Among several, the one sharing the longest path prefix with the importing file wins.
   */
  fun resolve(from: com.intellij.psi.PsiFile, path: String): VirtualFile? {
    val origin = from.virtualFile ?: return null
    origin.parent?.findFileByRelativePath(path)?.takeIf { !it.isDirectory }?.let { return it }
    val name = path.substringAfterLast('/')
    return FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.allScope(from.project))
      .filter { it.path.endsWith("/$path") }
      .sortedBy { it.path }
      .maxByOrNull { it.path.commonPrefixWith(origin.path).length }
  }

  /** Every message and enum by full name, from the file and the files it imports. */
  fun visibleTypes(file: ProtoFile): Map<String, ProtoTypeDefinition> = CachedValuesManager.getCachedValue(file) {
    val types = LinkedHashMap<String, ProtoTypeDefinition>()
    val files = listOf(file) + file.imports.mapNotNull { it.resolveFile() }.filter { it != file }
    for (f in files) {
      for (type in PsiTreeUtil.findChildrenOfType(f, ProtoTypeDefinition::class.java)) {
        types.putIfAbsent(type.qualifiedName, type)
      }
    }
    CachedValueProvider.Result.create(types, PsiModificationTracker.MODIFICATION_COUNT)
  }
}

object ProtoElementFactory {
  fun file(project: com.intellij.openapi.project.Project, text: String): ProtoFile =
    PsiFileFactory.getInstance(project).createFileFromText("dummy.proto", ProtoFileType.INSTANCE, text) as ProtoFile
}
