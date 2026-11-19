// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiModifier
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Kotlin entities against the compiler plugins the module's Gradle build applies (see [KotlinCompilerPlugins]):
 * - an entity that stays final because no `allOpen { annotation("…Entity") }` covers it, so Hibernate cannot proxy it
 *   (and an `open` that such a block makes redundant)
 * - no no-argument constructor and no `kotlin-jpa` plugin to generate one (and a hand-written one the plugin makes redundant)
 * - a `data class` entity, whose generated equals/hashCode/toString read every property, lazy associations included
 * - `val` attributes, which Hibernate assigns through reflection although the code treats them as fixed
 * - read-only `List`/`Set` types on to-many associations, which Hibernate fills and changes in place
 *
 * Silent when the module has no Gradle build script, since then the compiler plugins are unknown.
 */
open class KotlinJpaEntityInspection : AbstractBaseUastLocalInspectionTool() {
  /** The `val` check is its own inspection, so it can be turned off alone: some teams keep entity fields immutable on purpose. */
  protected open val valAttributesOnly: Boolean = false

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (holder.file.language.id != "kotlin") return PsiElementVisitor.EMPTY_VISITOR
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder, KotlinCompilerPlugins.of(module), valAttributesOnly), arrayOf(UClass::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder, private val plugins: KotlinCompilerPlugins.Config?, private val valOnly: Boolean) : AbstractUastNonRecursiveVisitor() {
    override fun visitClass(node: UClass): Boolean {
      val psiClass = node.javaPsi
      if (!JpaModel.isPersistent(psiClass)) return true
      val source = node.sourcePsi ?: return true
      val anchor = node.uastAnchor?.sourcePsi ?: return true
      val modifiers = KotlinSource.modifierKeywords(source)
      val entity = JpaModel.isEntity(psiClass)
      if (valOnly) {
        for (field in node.fields) checkVal(field)
        return true
      }

      if (entity && "data" in modifiers) holder.registerProblem(anchor, PhantomJpaBundle.message("kotlin.data.class", psiClass.name ?: ""))
      if (plugins != null) {
        val annotations = node.uAnnotations.mapNotNull { it.qualifiedName }
        val opened = plugins.opens(annotations)
        if (entity && !opened && "abstract" !in modifiers && "sealed" !in modifiers &&
            (psiClass.hasModifierProperty(PsiModifier.FINAL) || hasFinalAccessors(node))) {
          val fix = if ("open" in modifiers) LocalQuickFix.EMPTY_ARRAY else arrayOf<LocalQuickFix>(MakeOpenFix(source))
          holder.registerProblem(anchor, PhantomJpaBundle.message("kotlin.final.entity", psiClass.name ?: ""), *fix)
        }
        if (opened && "open" in modifiers) {
          KotlinSource.modifier(source, "open")?.let {
            holder.registerProblem(it, PhantomJpaBundle.message("kotlin.redundant.open"), ProblemHighlightType.LIKE_UNUSED_SYMBOL, RemoveKeywordFix())
          }
        }
        if (entity || JpaMapping.has(node, "Embeddable")) checkConstructors(node, anchor)
      }
      for (field in node.fields) checkField(field)
      return true
    }

    /** In an `open` class without all-open, properties are still final, so a proxy cannot intercept their getters. */
    private fun hasFinalAccessors(node: UClass): Boolean = node.methods.any { m ->
      !m.isConstructor && !m.isStatic && m.visibility != org.jetbrains.uast.UastVisibility.PRIVATE && m.javaPsi.hasModifierProperty(PsiModifier.FINAL)
    }

    private fun checkConstructors(node: UClass, anchor: PsiElement) {
      val constructors = node.javaPsi.constructors
      val noArg = constructors.firstOrNull { it.parameterList.isEmpty }
      if (plugins!!.noArgForEntities) {
        // the plugin generates the constructor, so a hand-written empty secondary constructor only adds noise
        val written = node.methods.firstOrNull { it.isConstructor && it.uastParameters.isEmpty() && KotlinSource.isSecondaryConstructor(it.sourcePsi) }
        written?.uastAnchor?.sourcePsi?.let { holder.registerProblem(it, PhantomJpaBundle.message("kotlin.redundant.constructor"), ProblemHighlightType.LIKE_UNUSED_SYMBOL) }
      }
      else if (constructors.isNotEmpty() && noArg == null) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("kotlin.no.default.constructor", node.javaPsi.name ?: ""))
      }
    }

    /**
     * A non-null Kotlin type on an attribute the mapping explicitly declares nullable: the column may hold NULL, and
     * reading such a row puts null into a property Kotlin assumes is never null (or fails for `Int`, `Long`, ...).
     */
    private fun checkNullability(field: UField, source: PsiElement) {
      if (KotlinSource.isNullableType(source) != false) return
      if (JpaMapping.has(field, "Id", "EmbeddedId", "Version")) return
      for ((annotationName, attribute) in NULLABLE_ATTRIBUTES) {
        val annotation = JpaMapping.annotation(field, annotationName) ?: continue
        if (annotation.findDeclaredAttributeValue(attribute)?.evaluate() != true) continue
        val anchor = annotation.sourcePsi?.takeIf { it.containingFile == holder.file } ?: field.uastAnchor?.sourcePsi ?: return
        holder.registerProblem(anchor, PhantomJpaBundle.message("kotlin.nullable.column", field.name, "@$annotationName($attribute = true)"))
        return
      }
    }

    private fun checkVal(field: UField) {
      val source = field.sourcePsi ?: return
      if (field.isStatic || !KotlinSource.isProperty(source)) return
      if (JpaMapping.has(field, "Transient")) return
      val keyword = KotlinSource.valOrVar(source)
      val relation = JpaMapping.relation(field)
      val toMany = relation != null && JpaMapping.kind(relation) in setOf("OneToMany", "ManyToMany")
      // `val items: MutableList<…>` is the usual shape: the collection object stays, its contents change
      if (keyword?.text == "val" && !toMany && !JpaMapping.has(field, "ElementCollection") &&
          !JpaMapping.has(field, "Id") && !JpaMapping.has(field, "EmbeddedId")) {
        holder.registerProblem(keyword, PhantomJpaBundle.message("kotlin.val.attribute", field.name), ValToVarFix())
      }
    }

    private fun checkField(field: UField) {
      val source = field.sourcePsi ?: return
      if (field.isStatic || !KotlinSource.isProperty(source)) return
      if (JpaMapping.has(field, "Transient")) return
      checkNullability(field, source)
      val relation = JpaMapping.relation(field)
      val toMany = relation != null && JpaMapping.kind(relation) in setOf("OneToMany", "ManyToMany")
      if (toMany) {
        val readOnly = KotlinSource.readOnlyCollectionType(source)
        if (readOnly != null) {
          holder.registerProblem(readOnly, PhantomJpaBundle.message("kotlin.read.only.collection", readOnly.text.substringBefore('<')),
                                 ProblemHighlightType.WEAK_WARNING)
        }
      }
    }
  }

  private class MakeOpenFix(source: PsiElement) : LocalQuickFix {
    private val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(source)
    override fun getFamilyName(): String = PhantomJpaBundle.message("kotlin.fix.make.open")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val source = pointer.element ?: return
      val classKeyword = KotlinSource.classKeyword(source) ?: return
      edit(project, source) { it.insertString(classKeyword.textRange.startOffset, "open ") }
    }
  }

  /** Removes the keyword the problem points at, with the space after it. */
  private class RemoveKeywordFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomJpaBundle.message("kotlin.fix.remove.open")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val keyword = descriptor.psiElement ?: return
      edit(project, keyword) { document ->
        var end = keyword.textRange.endOffset
        while (end < document.textLength && document.charsSequence[end] == ' ') end++
        document.deleteString(keyword.textRange.startOffset, end)
      }
    }
  }

  private class ValToVarFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomJpaBundle.message("kotlin.fix.val.to.var")
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val keyword = descriptor.psiElement ?: return
      edit(project, keyword) { it.replaceString(keyword.textRange.startOffset, keyword.textRange.endOffset, "var") }
    }
  }

  private companion object {
    fun edit(project: Project, element: PsiElement, change: (com.intellij.openapi.editor.Document) -> Unit) {
      val manager = PsiDocumentManager.getInstance(project)
      val document = manager.getDocument(element.containingFile) ?: return
      manager.doPostponedOperationsAndUnblockDocument(document)
      change(document)
      manager.commitDocument(document)
    }
  }
}

/**
 * Kotlin syntax read without the Kotlin plugin's PSI classes (this plugin does not depend on Kotlin): elements are
 * recognized by their PSI class names and leaf texts.
 */
private val NULLABLE_ATTRIBUTES = listOf("Column" to "nullable", "JoinColumn" to "nullable", "ManyToOne" to "optional", "OneToOne" to "optional")

internal object KotlinSource {
  private val KEYWORDS = setOf("open", "abstract", "sealed", "data", "final", "inner", "enum", "annotation", "value", "private", "internal", "protected", "public")

  private fun modifierList(declaration: PsiElement): PsiElement? =
    declaration.children.firstOrNull { it.javaClass.simpleName.endsWith("ModifierList") }

  fun modifierKeywords(declaration: PsiElement): Set<String> =
    modifierList(declaration)?.node?.getChildren(null)?.map { it.text }?.filter { it in KEYWORDS }?.toSet().orEmpty()

  fun modifier(declaration: PsiElement, keyword: String): PsiElement? =
    modifierList(declaration)?.node?.getChildren(null)?.firstOrNull { it.text == keyword }?.psi

  fun classKeyword(declaration: PsiElement): PsiElement? =
    declaration.node.getChildren(null).firstOrNull { it.text == "class" }?.psi

  fun isProperty(source: PsiElement): Boolean = source.javaClass.simpleName.let { it == "KtProperty" || it == "KtParameter" }

  fun isSecondaryConstructor(source: PsiElement?): Boolean = source?.javaClass?.simpleName == "KtSecondaryConstructor"

  /** Whether the declared type is nullable (`String?`); null when the type is inferred and not written. */
  fun isNullableType(source: PsiElement): Boolean? {
    val typeReference = source.children.firstOrNull { it.javaClass.simpleName == "KtTypeReference" } ?: return null
    return typeReference.text.trim().endsWith("?")
  }

  fun valOrVar(source: PsiElement): PsiElement? = source.node.getChildren(null).firstOrNull { it.text == "val" || it.text == "var" }?.psi

  /** The declared type when it is a read-only `List`, `Set`, `Collection` or `Map` (not their `Mutable…` forms). */
  fun readOnlyCollectionType(source: PsiElement): PsiElement? {
    val typeReference = source.children.firstOrNull { it.javaClass.simpleName == "KtTypeReference" } ?: return null
    return typeReference.takeIf { Regex("""^(kotlin\.collections\.)?(List|Set|Collection|Map)\s*<""").containsMatchIn(it.text) }
  }
}

class KotlinJpaValAttributeInspection : KotlinJpaEntityInspection() {
  override val valAttributesOnly: Boolean = true
}
