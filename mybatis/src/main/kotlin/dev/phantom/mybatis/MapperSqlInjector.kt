// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
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
 * Phantom Database Console's SQL (highlighting, completion, navigation, and syntax errors when the statement reads
 * whole: see [MapperSql.isWhole]). Without Phantom Database Console nothing is injected.
 */
class MapperSqlInjector : MultiHostInjector {
  override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlTag::class.java, PsiAnnotation::class.java)

  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    val sql = Language.findLanguageByID("SQL") ?: return
    val chunks = when (context) {
      is XmlTag -> MapperSql.chunks(context)
      is PsiAnnotation -> MapperSql.chunks(context)
      else -> null
    } ?: return
    val places = MapperSql.places(chunks)
    if (places.isEmpty()) return
    registrar.startInjecting(sql)
    for ((i, place) in places.withIndex()) {
      registrar.addPlace(place.prefix.ifEmpty { null }, if (i == places.lastIndex) place.suffix.ifEmpty { null } else null, place.host, place.range)
    }
    registrar.doneInjecting()
  }
}

/**
 * A statement as MyBatis assembles it: the text, `#{...}` / `${...}` holes, and what the dynamic tags add.
 * `<where>` adds `WHERE` (and `1=1` before a leading AND / OR, which MyBatis would drop), `<set>` adds `SET` and
 * drops the last comma, `<trim>` adds its prefix and suffix and drops its overrides, `<foreach>` adds open and close
 * around one item, `<include>` adds the `<sql>` it names. `<if>` and the branches of `<choose>` are all kept (only
 * the first branch when [Mode.RUN]).
 */
object MapperSql {
  sealed interface Chunk
  /** [range] of [host]'s text, copied as it is. */
  class Text(val host: PsiLanguageInjectionHost, val range: TextRange) : Chunk
  /** `#{...}` or `${...}`. */
  class Hole(val text: String) : Chunk
  /** Text the tags add. */
  class Extra(val text: String) : Chunk

  enum class Mode { EDIT, RUN }

  class Place(val prefix: String, val host: PsiLanguageInjectionHost, val range: TextRange, var suffix: String = "")

  private val HOLE = Regex("[#$]\\{[^}\\n]*}?|<!\\[CDATA\\[|]]>")
  private val LEADING_AND_OR = Regex("^\\s*(?i:and|or)\\b")

  /** The chunks of a statement or `<sql>` tag directly under `<mapper>`; null for any other tag. */
  fun chunks(tag: XmlTag, mode: Mode = Mode.EDIT): List<Chunk>? {
    if (tag.name !in MyBatis.STATEMENTS && tag.name != "sql") return null
    val root = MyBatis.mapperOf(tag) ?: return null
    if (tag.parentTag != root) return null
    return Builder(mode, 0).also { it.walk(tag) }.result
  }

  /** The string literals of a MyBatis SQL annotation, in order; `<script>` SQL is left alone. */
  fun chunks(annotation: PsiAnnotation): List<Chunk>? {
    if (annotation.qualifiedName !in MyBatis.SQL_ANNOTATIONS) return null
    val value = annotation.findDeclaredAttributeValue("value") ?: return null
    val literals = when (value) {
      is PsiArrayInitializerMemberValue -> value.initializers.toList()
      else -> listOf(value)
    }.map { it as? PsiLiteralExpression ?: return null }
    if (literals.any { it !is PsiLanguageInjectionHost || it.value !is String }) return null
    if ((literals.firstOrNull()?.value as? String)?.trimStart()?.startsWith("<script>") == true) return null
    val builder = Builder(Mode.EDIT, 0)
    for ((i, literal) in literals.withIndex()) {
      // MyBatis joins the array elements with a space
      if (i > 0) builder.result += Extra(" ")
      builder.text(literal as PsiLanguageInjectionHost, ElementManipulators.getValueTextRange(literal))
    }
    return builder.result
  }

  /** Injection places: holes read as `?`, added text goes into the prefix of the next place. */
  fun places(chunks: List<Chunk>): List<Place> {
    val result = ArrayList<Place>()
    val pending = StringBuilder()
    for (c in chunks) when (c) {
      is Text -> { result += Place(pending.toString(), c.host, c.range); pending.setLength(0) }
      is Hole -> pending.append('?')
      is Extra -> pending.append(c.text)
    }
    if (pending.isNotEmpty()) result.lastOrNull()?.suffix = pending.toString()
    return result
  }

  /** The SQL the places stand for, as the injected file reads it (XML entities as written). */
  fun text(places: List<Place>): String = places.joinToString("") { it.prefix + it.range.substring(it.host.text) + it.suffix }

  /**
   * SQL to run: XML entities decoded, `#{a.b, jdbcType=X}` as the named parameter `:a_b` and `${x}` as is, both of
   * which Phantom Database Console asks a value for.
   */
  fun runnable(chunks: List<Chunk>): String = buildString {
    for (c in chunks) when (c) {
      is Text -> {
        val raw = c.range.substring(c.host.text)
        append(if (c.host is XmlText) StringUtil.unescapeXmlEntities(raw) else StringUtil.unescapeStringCharacters(raw))
      }
      is Hole -> append(runnableHole(c.text))
      is Extra -> append(c.text)
    }
  }.lines().joinToString("\n") { it.trimEnd() }.trimIndent().trim()

  private fun runnableHole(hole: String): String {
    if (hole.startsWith("$")) return hole
    val name = hole.removePrefix("#{").removeSuffix("}").substringBefore(',').trim()
    return if (name.matches(Regex("[A-Za-z_][\\w.]*"))) ":" + name.replace('.', '_') else "?"
  }

  /**
   * The statement reads as one whole SQL statement, so syntax errors are real: no `${...}` (it may stand for a name
   * or a clause), no `<choose>` (its branches are all kept) and every `<include>` found.
   */
  fun isWhole(tag: XmlTag): Boolean {
    if (tag.name !in MyBatis.STATEMENTS) return false
    if (tag.value.text.contains("\${")) return false
    var whole = true
    fun visit(t: XmlTag, depth: Int) {
      for (child in t.subTags) {
        when (child.name) {
          "choose" -> whole = false
          "include" -> {
            val target = includeTarget(child)
            if (target == null || depth > 8 || target.value.text.contains("\${")) whole = false else visit(target, depth + 1)
          }
          else -> visit(child, depth)
        }
      }
    }
    visit(tag, 0)
    return whole
  }

  fun isWhole(annotation: PsiAnnotation): Boolean =
    annotation.qualifiedName in MyBatis.SQL_ANNOTATIONS && annotation.findDeclaredAttributeValue("value")?.text?.contains("\${") == false

  /** The `<sql>` an `<include refid>` names, here or in another mapper. */
  fun includeTarget(include: XmlTag): XmlTag? {
    // not through the refid's references: computing those asks for injections, which come here
    val id = include.getAttributeValue("refid") ?: return null
    return MyBatis.findTag(include, "sql", id)
  }

  private class Builder(val mode: Mode, val depth: Int) {
    val result = ArrayList<Chunk>()
    /** Ranges of texts that MyBatis drops: overrides of `<trim>` and `<set>`. */
    private val dropped = HashMap<XmlText, MutableList<TextRange>>()

    fun walk(tag: XmlTag) {
      for (child in tag.children) {
        when (child) {
          is XmlText -> text(child as PsiLanguageInjectionHost, TextRange(0, child.textLength))
          is XmlTag -> element(child)
        }
      }
    }

    private fun element(tag: XmlTag) {
      when (tag.name) {
        "where" -> {
          result += Extra(if (firstText(tag)?.let { LEADING_AND_OR.containsMatchIn(it.text) } == true) " WHERE 1=1 " else " WHERE ")
          walk(tag)
          result += Extra(" ")
        }
        "set" -> {
          dropSuffix(tag, listOf(","))
          result += Extra(" SET ")
          walk(tag)
          result += Extra(" ")
        }
        "trim" -> {
          dropPrefix(tag, overrides(tag.getAttributeValue("prefixOverrides")))
          dropSuffix(tag, overrides(tag.getAttributeValue("suffixOverrides")))
          result += Extra(" " + tag.getAttributeValue("prefix").orEmpty() + " ")
          walk(tag)
          result += Extra(" " + tag.getAttributeValue("suffix").orEmpty() + " ")
        }
        "foreach" -> {
          result += Extra(" " + tag.getAttributeValue("open").orEmpty())
          walk(tag)
          result += Extra(tag.getAttributeValue("close").orEmpty() + " ")
        }
        "include" -> {
          val target = includeTarget(tag)
          if (target == null || depth > 8) { result += Extra(" "); return }
          val inner = Builder(mode, depth + 1).also { it.walk(target) }.result
          val text = if (mode == Mode.RUN) runnable(inner) else places(inner).let { p ->
            p.joinToString("") { it.prefix + it.range.substring(it.host.text) + it.suffix }
          }
          result += Extra(" $text ")
        }
        "choose" -> {
          if (mode == Mode.RUN) {
            (tag.subTags.firstOrNull { it.name == "when" } ?: tag.subTags.firstOrNull { it.name == "otherwise" })?.let { walk(it) }
          }
          else walk(tag)
        }
        "bind", "selectKey" -> result += Extra(" ")
        else -> {
          // if, when, otherwise: MyBatis joins the parts of a dynamic statement with spaces
          result += Extra(" ")
          walk(tag)
          result += Extra(" ")
        }
      }
    }

    /** [range] of [host] without its holes, CDATA marks and dropped overrides. */
    fun text(host: PsiLanguageInjectionHost, range: TextRange) {
      val text = host.text
      val cuts = ArrayList<Pair<TextRange, String?>>()
      for (m in HOLE.findAll(text.substring(range.startOffset, range.endOffset))) {
        val r = TextRange(range.startOffset + m.range.first, range.startOffset + m.range.last + 1)
        cuts += r to (if (m.value.startsWith("#") || m.value.startsWith("$")) m.value else null)
      }
      (host as? XmlText)?.let { t -> dropped[t]?.forEach { cuts += it to null } }
      cuts.sortBy { it.first.startOffset }
      var start = range.startOffset
      for ((cut, hole) in cuts) {
        if (cut.startOffset < start) continue
        if (cut.startOffset > start) result += Text(host, TextRange(start, cut.startOffset))
        if (hole != null) result += Hole(hole)
        start = cut.endOffset
      }
      if (range.endOffset > start) result += Text(host, TextRange(start, range.endOffset))
    }

    private fun overrides(attribute: String?): List<String> = attribute?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun dropPrefix(tag: XmlTag, words: List<String>) {
      val text = firstText(tag) ?: return
      val raw = text.text
      val at = raw.indexOfFirst { !it.isWhitespace() }
      val word = words.firstOrNull { raw.regionMatches(at, it, 0, it.length, ignoreCase = true) } ?: return
      dropped.getOrPut(text) { ArrayList() } += TextRange(at, at + word.length)
    }

    private fun dropSuffix(tag: XmlTag, words: List<String>) {
      val text = lastText(tag) ?: return
      val raw = text.text
      val end = raw.indexOfLast { !it.isWhitespace() } + 1
      val word = words.firstOrNull { end >= it.length && raw.regionMatches(end - it.length, it, 0, it.length, ignoreCase = true) } ?: return
      dropped.getOrPut(text) { ArrayList() } += TextRange(end - word.length, end)
    }

    private fun texts(tag: XmlTag): List<XmlText> =
      com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(tag, XmlText::class.java).filter { it.text.isNotBlank() }

    private fun firstText(tag: XmlTag): XmlText? = texts(tag).firstOrNull()
    private fun lastText(tag: XmlTag): XmlText? = texts(tag).lastOrNull()
  }
}
