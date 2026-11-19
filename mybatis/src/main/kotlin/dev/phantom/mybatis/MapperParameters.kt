// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/** What `#{...}` and `${...}` can name inside a statement: parameters, their properties, and foreach / bind variables. */
object MapperParameters {
  private val OPEN = Regex("[#$]\\{([\\w.]*)$")

  /** The path typed so far after `#{` ending at [offset] of [text]; null when the caret is not inside one. */
  fun typedPath(text: CharSequence, offset: Int): String? {
    val from = maxOf(0, offset - 200)
    return OPEN.find(text.subSequence(from, offset))?.groupValues?.get(1)
  }

  /** Top-level names with their types (null when unknown). */
  fun roots(method: PsiMethod, context: XmlTag?): Map<String, PsiType?> {
    val result = LinkedHashMap<String, PsiType?>()
    // variables of enclosing <foreach item="x"> and <bind name="x">
    var tag = context
    while (tag != null && tag.name !in MyBatis.STATEMENTS) {
      if (tag.name == "foreach") {
        val collection = tag.getAttributeValue("collection")
        val item = tag.getAttributeValue("item")
        if (item != null) result[item] = collection?.let { MyBatis.elementType(typeOf(method, it)) }
        tag.getAttributeValue("index")?.let { result[it] = null }
      }
      tag.parentTag?.subTags?.filter { it.name == "bind" }?.forEach { b -> b.getAttributeValue("name")?.let { result.putIfAbsent(it, null) } }
      tag = tag.parentTag
    }
    tag?.subTags?.filter { it.name == "bind" }?.forEach { b -> b.getAttributeValue("name")?.let { result.putIfAbsent(it, null) } }
    val bean = MyBatis.beanParameter(method)
    if (bean != null) MyBatis.properties(bean).forEach { (name, type) -> result.putIfAbsent(name, type) }
    val parameters = method.parameterList.parameters
    for ((i, p) in parameters.withIndex()) {
      result.putIfAbsent(MyBatis.parameterName(p), p.type)
      if (parameters.size > 1 || p.hasAnnotation(MyBatis.PARAM)) result.putIfAbsent("param${i + 1}", p.type)
    }
    return result
  }

  /** The type of `a.b.c` among the method's parameters. */
  fun typeOf(method: PsiMethod, path: String, context: XmlTag? = null): PsiType? {
    val segments = path.split('.')
    var type = roots(method, context)[segments.first()] ?: return null
    for (s in segments.drop(1)) {
      val cls: PsiClass = MyBatis.beanClass(type) ?: return null
      type = MyBatis.properties(cls)[s] ?: return null
    }
    return type
  }
}

class MapperParameterCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val file = parameters.originalFile
    val offset = parameters.offset
    val path = MapperParameters.typedPath(file.viewProvider.contents, offset) ?: return
    val position = parameters.originalPosition ?: parameters.position
    val method = MyBatis.methodAround(position) ?: return
    if (file !is XmlFile && MyBatis.SQL_ANNOTATIONS.none { method.hasAnnotation(it) }) return
    val context = PsiTreeUtil.getParentOfType(position, XmlTag::class.java, false)
    val dot = path.lastIndexOf('.')
    val names: Map<String, PsiType?> = if (dot < 0) MapperParameters.roots(method, context)
    else {
      val owner = MyBatis.beanClass(MapperParameters.typeOf(method, path.substring(0, dot), context)) ?: return
      MyBatis.properties(owner)
    }
    val set = result.withPrefixMatcher(path.substring(dot + 1))
    for ((name, type) in names) {
      set.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Parameter).withTypeText(type?.presentableText))
    }
    set.stopHere()
  }
}

/** Opens completion right after `#{` or `${` in mapper XML and MyBatis annotations. */
class MapperParameterTypedHandler : TypedHandlerDelegate() {
  override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (charTyped != '{') return Result.CONTINUE
    val offset = editor.caretModel.offset
    val text = editor.document.charsSequence
    if (offset == 0 || text[offset - 1] != '#' && text[offset - 1] != '$') return Result.CONTINUE
    if (MyBatis.mapperRoot(file) == null && !text.contains("org.apache.ibatis")) return Result.CONTINUE
    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
    return Result.CONTINUE
  }
}
