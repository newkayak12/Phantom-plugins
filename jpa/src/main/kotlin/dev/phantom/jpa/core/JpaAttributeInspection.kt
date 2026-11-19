// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.jvm.actions.annotationRequest
import com.intellij.lang.jvm.actions.constantAttribute
import com.intellij.lang.jvm.actions.createAddAnnotationActions
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.IntentionWrapper
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtil
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Attribute mappings that start but behave differently than they read:
 * - `@Column` on a `@ManyToOne`/`@OneToOne` (the join column is configured with `@JoinColumn`)
 * - `fetch = LAZY` on the `mappedBy` side of a `@OneToOne`, which Hibernate loads eagerly anyway without bytecode enhancement
 * - `@ManyToMany` cascading REMOVE (or ALL), which deletes rows still shared with other owners
 * - a collection, map or entity typed field with no association, `@ElementCollection` or converter
 * - an enum without `@Enumerated`, stored as its ordinal so reordering the constants changes stored data
 */
class JpaAttributeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, Visitor(holder, module), arrayOf(UClass::class.java), true)
  }

  private class Visitor(private val holder: ProblemsHolder, private val module: Module) : AbstractUastNonRecursiveVisitor() {
    override fun visitClass(node: UClass): Boolean {
      val psiClass = node.javaPsi
      if (!JpaModel.isPersistent(psiClass)) return true
      val fieldAccess = !JpaMapping.usesPropertyAccess(psiClass)
      for (field in node.fields) {
        if (field.isStatic || field.name.endsWith("\$delegate")) continue
        val anchor = field.uastAnchor?.sourcePsi ?: continue
        val relation = JpaMapping.relation(field)
        if (relation != null) checkRelation(field, relation, anchor)
        else if (fieldAccess && !field.hasModifierProperty(com.intellij.psi.PsiModifier.TRANSIENT)) checkBasic(field, anchor)
      }
      return true
    }

    private fun checkRelation(field: UField, relation: UAnnotation, anchor: com.intellij.psi.PsiElement) {
      val kind = JpaMapping.kind(relation)
      val column = JpaMapping.annotation(field, "Column")
      if (column != null && kind in setOf("ManyToOne", "OneToOne")) {
        val fixable = listOf("length", "precision", "scale").none { column.findDeclaredAttributeValue(it) != null }
        val fixes = if (fixable) arrayOf<LocalQuickFix>(ColumnToJoinColumnFix()) else LocalQuickFix.EMPTY_ARRAY
        holder.registerProblem(column.sourcePsi ?: anchor, PhantomJpaBundle.message("attribute.column.on.association", kind), *fixes)
      }
      if (kind == "OneToOne" && JpaMapping.mappedBy(relation) != null && JpaMapping.attributeText(relation, "fetch")?.contains("LAZY") == true) {
        holder.registerProblem(relation.findDeclaredAttributeValue("fetch")?.sourcePsi ?: anchor, PhantomJpaBundle.message("attribute.lazy.inverse.one.to.one"))
      }
      if (kind == "ManyToMany" && JpaMapping.cascadesRemoval(relation)) {
        holder.registerProblem(relation.findDeclaredAttributeValue("cascade")?.sourcePsi ?: anchor, PhantomJpaBundle.message("attribute.many.to.many.remove"))
      }
    }

    private fun checkBasic(field: UField, anchor: com.intellij.psi.PsiElement) {
      if (JpaMapping.hasTypeMapping(field) || JpaMapping.has(field, "Id")) return
      val type = field.type
      if (JpaMapping.isCollectionOrMap(type)) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("attribute.collection.unmapped", field.name))
        return
      }
      val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return
      if (JpaModel.isEntity(psiClass)) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("attribute.entity.unmapped", field.name, psiClass.name ?: ""))
        return
      }
      if (psiClass.isEnum && !JpaMapping.has(field, "Enumerated") && type is PsiClassType &&
          psiClass.qualifiedName !in JpaMapping.autoAppliedTypes(module)) {
        holder.registerProblem(anchor, PhantomJpaBundle.message("attribute.enum.ordinal", field.name), ProblemHighlightType.WEAK_WARNING,
                               *enumeratedFix(field))
      }
    }

    private fun enumeratedFix(field: UField): Array<LocalQuickFix> {
      val owner = field.javaPsi as? JvmModifiersOwner ?: return LocalQuickFix.EMPTY_ARRAY
      val annotation = field.uAnnotations.firstOrNull()?.qualifiedName.orEmpty()
      val namespace = if (annotation.startsWith("javax.persistence")) "javax.persistence" else "jakarta.persistence"
      val actions: List<IntentionAction> = createAddAnnotationActions(owner, annotationRequest(
        "$namespace.Enumerated", constantAttribute("value", "$namespace.EnumType.STRING")))
      return IntentionWrapper.wrapToQuickFixes(actions, holder.file).toTypedArray()
    }
  }

  /** Renames the annotation in place; `name`, `nullable`, `unique`, `insertable`, `updatable` and `columnDefinition` mean the same on `@JoinColumn`. */
  private class ColumnToJoinColumnFix : LocalQuickFix {
    override fun getFamilyName(): String = PhantomJpaBundle.message("attribute.fix.join.column")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val element = descriptor.psiElement ?: return
      val file = element.containingFile ?: return
      val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
      val start = element.textRange.startOffset
      val text = document.charsSequence
      val match = Regex("""@(?:[\w.]*\.)?Column\b""").find(text.subSequence(start, element.textRange.endOffset)) ?: return
      val nameEnd = start + match.range.last + 1
      document.replaceString(nameEnd - "Column".length, nameEnd, "JoinColumn")
      addImport(file, document)
      PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** Next to an explicit `Column` import; a wildcard or fully qualified use needs nothing. */
    private fun addImport(file: PsiFile, document: com.intellij.openapi.editor.Document) {
      val text = document.text
      val java = file.name.endsWith(".java")
      for (namespace in listOf("jakarta.persistence", "javax.persistence")) {
        val columnImport = Regex("""(?m)^import\s+${Regex.escape(namespace)}\.Column\s*;?[ \t]*$""").find(text) ?: continue
        if (Regex("""(?m)^import\s+${Regex.escape(namespace)}\.JoinColumn\b""").containsMatchIn(text)) return
        document.insertString(columnImport.range.last + 1, "\nimport $namespace.JoinColumn" + if (java) ";" else "")
        return
      }
    }
  }
}
