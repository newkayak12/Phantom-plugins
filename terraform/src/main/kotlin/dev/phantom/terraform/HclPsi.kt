// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.terraform

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import javax.swing.Icon

/** `type "label" "label" { body }`. The last label is the block's name: `resource "aws_instance" "web"` is `web`. */
class HclBlock(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  val type: String get() = node.findChildByType(HclTokens.WORDS)?.text.orEmpty()
  val labels: List<HclLabel> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HclLabel::class.java)
  val body: HclBody? get() = PsiTreeUtil.getChildOfType(this, HclBody::class.java)
  val blocks: List<HclBlock> get() = body?.blocks.orEmpty()
  val attributes: List<HclAttribute> get() = body?.attributes.orEmpty()

  fun attribute(name: String): HclAttribute? = attributes.firstOrNull { it.name == name }

  override fun getNameIdentifier(): PsiElement? = labels.lastOrNull()

  override fun getName(): String? = labels.lastOrNull()?.value

  override fun setName(name: String): PsiElement {
    val old = labels.lastOrNull() ?: throw IncorrectOperationException()
    val new = HclElementFactory.file(project, "x \"$name\" {}").blocks.firstOrNull()?.labels?.firstOrNull() ?: throw IncorrectOperationException(name)
    old.replace(new)
    return this
  }

  /** The start of the name inside the quotes, so the caret on it finds the block as the target of Find Usages and rename. */
  override fun getTextOffset(): Int {
    val label = labels.lastOrNull() ?: return super.getTextOffset()
    return label.textOffset + if (label.text.startsWith('"') && label.textLength > 1) 1 else 0
  }

  override fun getPresentation(): ItemPresentation = presentation((listOf(type) + labels.map { it.value }).joinToString(" "), this, AllIcons.Nodes.Class)

  override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Class
}

class HclLabel(node: ASTNode) : ASTWrapperPsiElement(node) {
  /** The label without its quotes. */
  val value: String get() = text.removeSurrounding("\"")
}

class HclBody(node: ASTNode) : ASTWrapperPsiElement(node) {
  val blocks: List<HclBlock> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HclBlock::class.java)
  val attributes: List<HclAttribute> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HclAttribute::class.java)
}

/** `name = value`; in a `locals` block the name declares `local.name`. */
class HclAttribute(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  override fun getNameIdentifier(): PsiElement? = firstChild?.takeIf { it.node.elementType in HclTokens.WORDS }

  override fun getName(): String? = nameIdentifier?.text

  /** The expression after `=`. */
  val value: PsiElement? get() = node.findChildByType(HclTokens.EQ)?.psi?.let { PsiTreeUtil.skipWhitespacesAndCommentsForward(it) }

  override fun setName(name: String): PsiElement {
    val old = nameIdentifier ?: throw IncorrectOperationException()
    val new = HclElementFactory.file(project, "$name = 1").attributes.firstOrNull()?.nameIdentifier ?: throw IncorrectOperationException(name)
    old.replace(new)
    return this
  }

  override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

  override fun getPresentation(): ItemPresentation = presentation(name.orEmpty(), this, AllIcons.Nodes.Field)

  override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Field
}

/** The name an expression starts with, as `var` in `var.region` or `each` in `each.key`. */
class HclVariable(node: ASTNode) : ASTWrapperPsiElement(node) {
  override fun getName(): String = text
}

/** An iteration variable of a for expression or a `%{for}` directive. */
class HclForVar(node: ASTNode) : ASTWrapperPsiElement(node) {
  override fun getName(): String = text
}

/** `qualifier.name`. Chains that start with a variable, such as `var.region` or `aws_instance.web.id`, refer to declarations. */
class HclGetAttr(node: ASTNode) : ASTWrapperPsiElement(node) {
  private val nameNode: ASTNode? get() = node.lastChildNode?.takeIf { it.elementType in HclTokens.WORDS }

  /** The names from the root variable to this one (`[aws_instance, web, id]`), or null when an index or call is in between. */
  val chain: List<String>?
    get() {
      val name = nameNode?.text ?: return null
      return when (val qualifier = firstChild) {
        is HclVariable -> listOf(qualifier.name, name)
        is HclGetAttr -> qualifier.chain?.plus(name)
        else -> null
      }
    }

  fun resolve(): PsiElement? {
    val file = containingFile as? HclFile ?: return null
    if (!file.isConfiguration || TerraformScopes.isLocalName(this)) return null
    return TerraformModules.resolve(file, chain ?: return null)
  }

  override fun getReference(): PsiReference? {
    val name = nameNode ?: return null
    return AttributeReference(this, TextRange.from(name.startOffsetInParent, name.textLength))
  }

  override fun getReferences(): Array<PsiReference> = reference?.let { arrayOf(it) } ?: PsiReference.EMPTY_ARRAY

  private class AttributeReference(element: HclGetAttr, range: TextRange) : PsiReferenceBase<HclGetAttr>(element, range, true) {
    override fun resolve(): PsiElement? = element.resolve()

    override fun handleElementRename(newElementName: String): PsiElement {
      val old = element.nameNode ?: return element
      val dummy = HclElementFactory.file(element.project, "x = a.$newElementName")
      val new = PsiTreeUtil.findChildOfType(dummy, HclGetAttr::class.java)?.nameNode ?: throw IncorrectOperationException(newElementName)
      element.node.replaceChild(old, new)
      return element
    }
  }
}

private fun presentation(text: String, element: PsiElement, icon: Icon): ItemPresentation = object : ItemPresentation {
  override fun getPresentableText(): String = text
  override fun getLocationString(): String? = element.containingFile?.name
  override fun getIcon(unused: Boolean): Icon = icon
}

object HclElementFactory {
  fun file(project: Project, text: String): HclFile =
    PsiFileFactory.getInstance(project).createFileFromText("dummy.tf", TerraformFileType.INSTANCE, text) as HclFile
}
