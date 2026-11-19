// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor

/**
 * Go to Implementation (Cmd+Opt+B / Ctrl+Alt+B) on a mapper method, at its declaration or any call
 * (`userMapper.findById(id)` in a service), opens its XML statement; on the mapper interface, the mapper XML.
 */
class MapperDefinitionsSearch : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {
  override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
    when (val element = parameters.element) {
      is PsiMethod -> if (element.containingClass?.isInterface == true) MyBatis.statementsFor(element).forEach { if (!consumer.process(it)) return }
      is PsiClass -> if (element.isInterface) MyBatis.mapperFiles(element).mapNotNull { MyBatis.mapperRoot(it) }.forEach { if (!consumer.process(it)) return }
    }
  }
}

/**
 * Names in statements, to what they stand for (Cmd+click; in attributes and annotations also find usages):
 * `#{user.address.city}` and `${...}` in mapper XML and in `@Select` / `@Insert` / `@Update` / `@Delete`, and the
 * OGNL of `<if test>`, `<when test>`, `<foreach collection>` and `<bind value>`. The first name is a parameter
 * (its `@Param` name or `paramN`), a property of a single bean parameter, or a `<foreach item>` / `<bind>`
 * variable; the next ones are properties.
 */
class MapperParameterReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    // XML text (the statement body) takes no references: see MapperXmlGotoDeclarationHandler
    registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(), object : PsiReferenceProvider() {
      override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attribute.parent ?: return PsiReference.EMPTY_ARRAY
        val ognl = when (tag.name) {
          "if", "when" -> attribute.name == "test"
          "foreach" -> attribute.name == "collection"
          "bind" -> attribute.name == "value"
          else -> false
        }
        if (!ognl || statementOf(tag) == null) return PsiReference.EMPTY_ARRAY
        return ParameterPaths.inOgnl(value, ElementManipulators.getValueTextRange(value)).toTypedArray()
      }
    })
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiLiteralExpression::class.java), object : PsiReferenceProvider() {
      override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        if ((literal.value as? String)?.contains('{') != true) return PsiReference.EMPTY_ARRAY
        val annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java) ?: return PsiReference.EMPTY_ARRAY
        if (annotation.qualifiedName !in MyBatis.SQL_ANNOTATIONS) return PsiReference.EMPTY_ARRAY
        return ParameterPaths.inPlaceholders(literal, ElementManipulators.getValueTextRange(literal)).toTypedArray()
      }
    })
  }

}

/** The statement tag [tag] is in. */
internal fun statementOf(tag: XmlTag?): XmlTag? {
  var t = tag
  while (t != null && !(t.name in MyBatis.STATEMENTS && t.parentTag?.name == "mapper")) t = t.parentTag
  return t?.takeIf { MyBatis.mapperOf(it) != null }
}

/** Cmd+click on `#{a.b}` in the body of a mapper XML statement: XML text takes no references, so it goes here. */
class MapperXmlGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
    val text = source?.parent as? XmlText ?: return null
    if (!text.text.contains('{') || statementOf(text.parentTag) == null) return null
    val at = offset - text.textRange.startOffset
    val reference = ParameterPaths.inPlaceholders(text, TextRange(0, text.textLength)).firstOrNull { it.rangeInElement.containsOffset(at) } ?: return null
    return reference.resolve()?.let { arrayOf(it) }
  }
}

object ParameterPaths {
  private val PLACEHOLDER = Regex("[#$]\\{\\s*([A-Za-z_][\\w.]*)")
  private val OGNL_PATH = Regex("(?<![\\w.@])[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")
  private val STRING = Regex("'[^']*'|\"[^\"]*\"|&quot;.*?&quot;|&apos;.*?&apos;")
  private val OGNL_WORDS = setOf("and", "or", "not", "null", "true", "false", "eq", "neq", "lt", "lte", "gt", "gte", "in", "instanceof",
                                 "_parameter", "_databaseId", "new")

  /** References for the paths of `#{...}` / `${...}` in [range] of [element]'s text. */
  fun inPlaceholders(element: PsiElement, range: TextRange): List<PsiReference> {
    val text = element.text
    return PLACEHOLDER.findAll(text.substring(range.startOffset, range.endOffset)).flatMap { m ->
      val group = m.groups[1]!!
      references(element, range.startOffset + group.range.first, group.value, followedByCall = false)
    }.toList()
  }

  /** References for the names in an OGNL expression; calls (`name.isEmpty()`) stop before the method. */
  fun inOgnl(element: PsiElement, range: TextRange): List<PsiReference> {
    val text = element.text.substring(range.startOffset, range.endOffset)
    val masked = STRING.replace(text) { " ".repeat(it.value.length) }
    return OGNL_PATH.findAll(masked).flatMap { m ->
      if (m.value.substringBefore('.') in OGNL_WORDS) return@flatMap emptySequence()
      val call = masked.getOrNull(m.range.last + 1) == '('
      references(element, range.startOffset + m.range.first, m.value, call)
    }.toList()
  }

  private fun references(element: PsiElement, start: Int, path: String, followedByCall: Boolean): Sequence<PsiReference> {
    val segments = path.split('.').filter { it.isNotEmpty() }.let { if (followedByCall) it.dropLast(1) else it }
    var offset = start
    return segments.mapIndexed { i, s ->
      val ref = ParameterPathReference(element, TextRange(offset, offset + s.length), segments.take(i + 1))
      offset += s.length + 1
      ref
    }.asSequence()
  }

  /** What the last name of [path] stands for. */
  fun resolve(method: PsiMethod, context: XmlTag?, path: List<String>): PsiElement? {
    if (path.size == 1) return resolveRoot(method, context, path[0])
    val owner = MyBatis.beanClass(MapperParameters.typeOf(method, path.dropLast(1).joinToString("."), context)) ?: return null
    return member(owner, path.last())
  }

  private fun resolveRoot(method: PsiMethod, context: XmlTag?, name: String): PsiElement? {
    var tag = context
    while (tag != null && tag.name !in MyBatis.STATEMENTS) {
      if (tag.name == "foreach") {
        for (attribute in listOf("item", "index")) if (tag.getAttributeValue(attribute) == name) return tag.getAttribute(attribute)?.valueElement
      }
      bindIn(tag.parentTag, name)?.let { return it }
      tag = tag.parentTag
    }
    bindIn(tag, name)?.let { return it }
    MyBatis.beanParameter(method)?.let { bean -> member(bean, name)?.let { return it } }
    val parameters = method.parameterList.parameters
    parameters.firstOrNull { MyBatis.parameterName(it) == name }?.let { return it }
    return Regex("param(\\d+)").matchEntire(name)?.let { parameters.getOrNull(it.groupValues[1].toInt() - 1) }
  }

  private fun bindIn(tag: XmlTag?, name: String): PsiElement? =
    tag?.subTags?.firstOrNull { it.name == "bind" && it.getAttributeValue("name") == name }?.getAttribute("name")?.valueElement

  private fun member(cls: PsiClass, name: String): PsiElement? =
    cls.findFieldByName(name, true) ?: PropertyUtilBase.findPropertyGetter(cls, name, false, true)
}

/** One name of a parameter path; soft: an unknown name is not an error (MyBatis also reads maps and plugins' extras). */
class ParameterPathReference(element: PsiElement, range: TextRange, private val path: List<String>) :
  PsiReferenceBase<PsiElement>(element, range, true) {

  override fun resolve(): PsiElement? {
    val method = MyBatis.methodAround(element) ?: return null
    val tag = when (val e = element) {
      is XmlText -> e.parentTag
      is XmlAttributeValue -> (e.parent?.parent as? XmlTag)?.let { if (it.name == "foreach") it.parentTag else it }
      else -> null
    }
    return ParameterPaths.resolve(method, tag, path)
  }

  /** Renaming a getter renames the property. */
  override fun handleElementRename(newElementName: String): PsiElement {
    val target = resolve()
    val name = if (target is PsiMethod) PropertyUtilBase.getPropertyName(newElementName) ?: newElementName else newElementName
    return super.handleElementRename(name)
  }
}
