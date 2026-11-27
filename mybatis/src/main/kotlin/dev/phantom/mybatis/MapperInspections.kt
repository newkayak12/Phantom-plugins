// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.psi.PsiFile
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.XmlElementFactory

/** Mapper XML: statements without a method, duplicate ids, `resultMap` and `refid` that name nothing. */
class MapperXmlInspection : LocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val root = MyBatis.mapperRoot(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    // How many tags of a kind share an id, counted once per file instead of rescanning the mapper for every tag.
    val idCounts by lazy(LazyThreadSafetyMode.NONE) {
      root.subTags.mapNotNull { t -> t.getAttributeValue("id")?.let { idKind(t) to it } }.groupingBy { it }.eachCount()
    }
    return object : XmlElementVisitor() {
      override fun visitXmlTag(tag: XmlTag) {
        if (tag == root) {
          val namespace = tag.getAttribute("namespace")
          // a namespace that is not a class name (some projects use free names with sqlSession calls) is left alone
          if (MyBatis.mapperClass(holder.file) == null && namespace?.value.orEmpty().contains('.') &&
              namespace?.value.orEmpty().substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            namespace?.valueElement?.let { holder.registerProblem(it, ElementManipulators.getValueTextRange(it), PhantomMyBatisBundle.message("xml.no.class", namespace.value.orEmpty())) }
          }
          return
        }
        if (tag.parentTag == root && tag.name in MyBatis.STATEMENTS + setOf("sql", "resultMap")) checkId(tag)
        if (tag.parentTag == root && tag.name in MyBatis.STATEMENTS) checkMethod(tag)
        tag.getAttribute("resultMap")?.valueElement?.let { v ->
          v.references.filterIsInstance<TagIdReference>().firstOrNull()?.takeIf { it.target() == null }
            ?.let { error(v, PhantomMyBatisBundle.message("xml.no.result.map", v.value)) }
        }
        if (tag.name == "include") tag.getAttribute("refid")?.valueElement?.let { v ->
          // ${...} refids are resolved at run time
          if (!v.value.contains("\${")) v.references.filterIsInstance<TagIdReference>().firstOrNull()?.takeIf { it.target() == null }
            ?.let { error(v, PhantomMyBatisBundle.message("xml.no.sql", v.value)) }
        }
      }

      private fun error(value: XmlAttributeValue, message: String) =
        holder.registerProblem(value, message, ProblemHighlightType.ERROR, ElementManipulators.getValueTextRange(value))

      private fun checkId(tag: XmlTag) {
        val id = tag.getAttributeValue("id") ?: return
        if ((idCounts[idKind(tag) to id] ?: 0) > 1) tag.getAttribute("id")?.valueElement?.let {
          error(it, PhantomMyBatisBundle.message("xml.duplicate.id", id))
        }
      }

      private fun checkMethod(tag: XmlTag) {
        val mapper = MyBatis.mapperClass(holder.file) ?: return
        val id = tag.getAttribute("id")?.valueElement ?: return
        if (MyBatis.methodsFor(tag).isEmpty()) {
          error(id, PhantomMyBatisBundle.message("xml.no.method", mapper.name.orEmpty(), id.value))
        }
      }
    }
  }
}

/** Statements share one id space; `<sql>` and `<resultMap>` each have their own. */
private fun idKind(tag: XmlTag): String = if (tag.name in MyBatis.STATEMENTS) "statement" else tag.name

/** A mapper method that has neither an XML statement nor SQL annotations, when the interface has a mapper XML. */
class MapperMethodInspection : AbstractBaseJavaLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {
    override fun visitMethod(method: PsiMethod) {
      val mapper = method.containingClass ?: return
      if (!mapper.isInterface || !MyBatis.needsStatement(method) || MyBatis.hasAnnotatedSql(method)) return
      val files = MyBatis.mapperFiles(mapper)
      if (files.isEmpty() || MyBatis.statementsFor(method).isNotEmpty()) return
      val identifier = method.nameIdentifier ?: return
      val file = files.first()
      val tag = GenerateStatementFix.tagFor(method)
      holder.registerProblem(identifier, PhantomMyBatisBundle.message("method.no.statement", method.name, file.name),
                             GenerateStatementFix(tag, method.name, file.name))
    }
  }
}

/** Writes `<select id="m" resultType="...">` (or insert / update / delete, from the method name) into the mapper XML. */
class GenerateStatementFix(private val tag: String, private val id: String, private val fileName: String) : LocalQuickFix {
  override fun getName(): String = PhantomMyBatisBundle.message("method.fix", tag, id, fileName)
  override fun getFamilyName(): String = PhantomMyBatisBundle.message("method.fix.family")

  // the statement goes into another file
  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

  // The XML file is not the one the problem is in: it is made writable (read-only or under VCS lock) before the write action.
  override fun startInWriteAction(): Boolean = false

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val method = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMethod::class.java) ?: return
    val file = MyBatis.mapperFiles(method.containingClass ?: return).firstOrNull() ?: return
    if (!FileModificationService.getInstance().prepareFileForWrite(file)) return
    val added = WriteCommandAction.writeCommandAction(project, file).withName(name).compute<XmlTag?, RuntimeException> { add(file, method) } ?: return
    val body = added.value.textRange.startOffset + 1
    file.virtualFile?.let { OpenFileDescriptor(project, it, body).navigate(true) }
  }

  companion object {
    fun tagFor(method: PsiMethod): String {
      val name = method.name.lowercase()
      return when {
        name.startsWith("insert") || name.startsWith("add") || name.startsWith("save") || name.startsWith("create") -> "insert"
        name.startsWith("update") || name.startsWith("modify") || name.startsWith("edit") -> "update"
        name.startsWith("delete") || name.startsWith("remove") -> "delete"
        else -> "select"
      }
    }

    /** Adds the statement for [method] at the end of the mapper; returns it. */
    fun add(file: XmlFile, method: PsiMethod): XmlTag? {
      val root = MyBatis.mapperRoot(file) ?: return null
      val tag = tagFor(method)
      val attributes = StringBuilder("id=\"${method.name}\"")
      if (tag == "select") {
        val result = MyBatis.elementType(method.returnType)
        if (result != null && result != PsiTypes.voidType()) {
          val text = (result as? com.intellij.psi.PsiClassType)?.rawType()?.canonicalText ?: result.canonicalText
          attributes.append(" resultType=\"").append(text.removePrefix("java.lang.")).append('"')
        }
      }
      val created = XmlElementFactory.getInstance(file.project).createTagFromText("<$tag $attributes>\n\n</$tag>")
      return root.addSubTag(created, false)
    }
  }
}
