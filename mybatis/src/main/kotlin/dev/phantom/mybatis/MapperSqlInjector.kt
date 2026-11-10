// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * The SQL of mapper XML statements and `<sql>` fragments, and of `@Select` / `@Insert` / `@Update` / `@Delete`, as
 * Phantom Database Console's SQL (highlighting, completion, navigation). Its syntax errors are not reported in
 * injected SQL, so the pieces between dynamic tags may join into SQL that does not parse.
 *
 * `#{...}` and `${...}` stay outside and read as `?`. `<where>`, `<set>`, `<trim prefix>` and `<foreach open close>`
 * add what MyBatis adds. Without Phantom Database Console nothing is injected.
 */
class MapperSqlInjector : MultiHostInjector {
  override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlTag::class.java, PsiAnnotation::class.java)

  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    val sql = Language.findLanguageByID("SQL") ?: return
    val places = when (context) {
      is XmlTag -> MapperSql.places(context)
      is PsiAnnotation -> MapperSql.places(context)
      else -> null
    }
    if (places.isNullOrEmpty()) return
    registrar.startInjecting(sql)
    for ((i, place) in places.withIndex()) {
      registrar.addPlace(place.prefix.ifEmpty { null }, if (i == places.lastIndex) place.suffix.ifEmpty { null } else null, place.host, place.range)
    }
    registrar.doneInjecting()
  }
}

object MapperSql {
  class Place(val prefix: String, val host: PsiLanguageInjectionHost, val range: TextRange, var suffix: String = "")

  private val HOLE = Regex("[#$]\\{[^}\\n]*}?|<!\\[CDATA\\[|]]>")

  /** The pieces of SQL of a statement or `<sql>` tag directly under `<mapper>`; null for any other tag. */
  fun places(tag: XmlTag): List<Place>? {
    if (tag.name !in MyBatis.STATEMENTS && tag.name != "sql") return null
    val root = MyBatis.mapperOf(tag) ?: return null
    if (tag.parentTag != root) return null
    val result = ArrayList<Place>()
    val pending = StringBuilder()
    walk(tag, result, pending)
    if (pending.isNotBlank()) result.lastOrNull()?.suffix = pending.toString()
    return result
  }

  /** The string literals of a MyBatis SQL annotation, in order; `<script>` SQL is left alone. */
  fun places(annotation: PsiAnnotation): List<Place>? {
    if (annotation.qualifiedName !in MyBatis.SQL_ANNOTATIONS) return null
    val value = annotation.findDeclaredAttributeValue("value") ?: return null
    val literals = when (value) {
      is PsiArrayInitializerMemberValue -> value.initializers.toList()
      else -> listOf(value)
    }.map { it as? PsiLiteralExpression ?: return null }
    if (literals.any { it !is PsiLanguageInjectionHost || it.value !is String }) return null
    if ((literals.firstOrNull()?.value as? String)?.trimStart()?.startsWith("<script>") == true) return null
    val result = ArrayList<Place>()
    val pending = StringBuilder()
    for (literal in literals) {
      // adjacent array elements are joined by MyBatis with a space
      if (result.isNotEmpty()) pending.append(' ')
      add(literal as PsiLanguageInjectionHost, ElementManipulators.getValueTextRange(literal), result, pending)
    }
    if (pending.isNotBlank()) result.lastOrNull()?.suffix = pending.toString()
    return result
  }

  /** The SQL the places stand for, as the injected file reads it. */
  fun text(places: List<Place>): String = places.joinToString("") { it.prefix + it.range.substring(it.host.text) + it.suffix }

  private fun walk(tag: XmlTag, result: MutableList<Place>, pending: StringBuilder) {
    for (child in tag.children) {
      when {
        child is XmlText -> add(child as PsiLanguageInjectionHost, TextRange(0, child.textLength), result, pending)
        child is XmlTag -> when (child.name) {
          "where" -> { pending.append(" WHERE "); walk(child, result, pending) }
          "set" -> { pending.append(" SET "); walk(child, result, pending) }
          "trim" -> {
            pending.append(' ').append(child.getAttributeValue("prefix").orEmpty()).append(' ')
            walk(child, result, pending)
            pending.append(' ').append(child.getAttributeValue("suffix").orEmpty()).append(' ')
          }
          "foreach" -> {
            pending.append(' ').append(child.getAttributeValue("open").orEmpty())
            walk(child, result, pending)
            pending.append(child.getAttributeValue("close").orEmpty()).append(' ')
          }
          "include", "bind", "selectKey" -> pending.append(' ')
          else -> walk(child, result, pending) // if, choose, when, otherwise
        }
      }
    }
  }

  /** [range] of [host] without its `#{...}`, `${...}` and CDATA marks; each hole reads as `?`. */
  private fun add(host: PsiLanguageInjectionHost, range: TextRange, result: MutableList<Place>, pending: StringBuilder) {
    val text = host.text
    var start = range.startOffset
    for (m in HOLE.findAll(text.substring(range.startOffset, range.endOffset))) {
      val holeStart = range.startOffset + m.range.first
      if (holeStart > start) {
        result += Place(pending.toString(), host, TextRange(start, holeStart))
        pending.setLength(0)
      }
      if (m.value.startsWith("#") || m.value.startsWith("$")) pending.append('?')
      start = range.startOffset + m.range.last + 1
    }
    if (range.endOffset > start) {
      result += Place(pending.toString(), host, TextRange(start, range.endOffset))
      pending.setLength(0)
    }
  }
}
