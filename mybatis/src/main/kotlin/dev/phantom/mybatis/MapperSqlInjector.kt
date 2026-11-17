// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
      registrar.addPlace(fence(place.prefix), fence(place.suffix), place.host, place.range)
    }
    registrar.doneInjecting()
  }

  /**
   * Added text that starts or ends with whitespace, between empty comments: formatting the XML replaces whitespace at
   * the edge of a host through the injected file, which must not reach whitespace that is not in the host.
   */
  private fun fence(text: String): String? = when {
    text.isEmpty() -> null
    text.first().isWhitespace() || text.last().isWhitespace() -> "/**/$text/**/"
    else -> text
  }
}

/**
 * A statement as MyBatis assembles it: the text, `#{...}` / `${...}` holes, and what the dynamic tags add.
 * `<where>` adds `WHERE` (and `1=1` before a leading AND / OR, which MyBatis would drop), `<set>` adds `SET` and
 * drops the last comma, `<trim>` adds its prefix and suffix and drops its overrides, `<foreach>` adds open and close
 * around one item, `<include>` adds the `<sql>` it names. `<if>` and the branches of `<choose>` are all kept, or
 * only the branch [chunks] is given. [runnable] builds the statement to run, with MyBatis' own rules.
 */
object MapperSql {
  sealed interface Chunk
  /** [range] of [host]'s text, copied as it is. */
  class Text(val host: PsiLanguageInjectionHost, val range: TextRange) : Chunk
  /** `#{...}` or `${...}` in [host]'s text. */
  class Hole(val text: String, val host: PsiLanguageInjectionHost? = null) : Chunk
  /** Text the tags add. */
  class Extra(val text: String) : Chunk

  /** What a run takes: the `<if>` / `<when>` parts left out ([off]) and the branch of each `<choose>` ([branch]). */
  class Choices(val off: Set<XmlTag> = emptySet(), val branch: Map<XmlTag, XmlTag> = emptyMap())

  class Place(val prefix: String, val host: PsiLanguageInjectionHost, val range: TextRange, var suffix: String = "")

  private val HOLE = Regex("[#$]\\{[^}\\n]*}?|<!\\[CDATA\\[|]]>")
  private val LEADING_AND_OR = Regex("^\\s*(?i:and|or)\\b")

  /**
   * The chunks of a statement or `<sql>` tag directly under `<mapper>`; null for any other tag. A `<choose>` in
   * [branch] keeps only that branch; [keepOverrides] keeps the text `<trim>` and `<set>` drop (for formatting).
   */
  fun chunks(tag: XmlTag, branch: Map<XmlTag, XmlTag> = emptyMap(), keepOverrides: Boolean = false): List<Chunk>? {
    if (tag.name !in MyBatis.STATEMENTS && tag.name != "sql") return null
    val root = MyBatis.mapperOf(tag) ?: return null
    if (tag.parentTag != root) return null
    return Builder(branch, keepOverrides, 0).also { it.walk(tag) }.result
  }

  /**
   * The ways a statement with `<choose>` reads: each branch of each `<choose>` once (the others at their first
   * branch). One way for a statement without.
   */
  fun variants(tag: XmlTag): List<List<Chunk>> {
    val chooses = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(tag, XmlTag::class.java).filter { it.name == "choose" }
    val first = chooses.mapNotNull { c -> branches(c).firstOrNull()?.let { c to it } }.toMap()
    val ways = LinkedHashSet<Map<XmlTag, XmlTag>>().apply { add(first) }
    for (c in chooses) for (b in branches(c)) if (ways.size < 32) ways += first + (c to b)
    return ways.mapNotNull { chunks(tag, it) }
  }

  /** The `<when>` and `<otherwise>` of a `<choose>`, in order. */
  fun branches(choose: XmlTag): List<XmlTag> = choose.subTags.filter { it.name == "when" || it.name == "otherwise" }

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
    val builder = Builder(emptyMap(), false, 0)
    for ((i, literal) in literals.withIndex()) {
      // MyBatis joins the array elements with a space
      if (i > 0) builder.result += Extra(" ")
      builder.text(literal as PsiLanguageInjectionHost, ElementManipulators.getValueTextRange(literal))
    }
    return builder.result
  }

  /**
   * Injection places: holes read as `?`, added text goes into the prefix of the next place. A hole right after text of
   * its own host goes into that place's suffix, so that it stays with its host when the text after it is dropped.
   */
  fun places(chunks: List<Chunk>): List<Place> {
    val result = ArrayList<Place>()
    val pending = StringBuilder()
    for (c in chunks) when (c) {
      is Text -> { result += Place(pending.toString(), c.host, c.range); pending.setLength(0) }
      is Hole -> {
        val last = result.lastOrNull()
        if (pending.isEmpty() && last != null && c.host != null && last.host === c.host) last.suffix += "?" else pending.append('?')
      }
      is Extra -> pending.append(c.text)
    }
    if (pending.isNotEmpty()) result.lastOrNull()?.let { it.suffix += pending }
    return result
  }

  /** The SQL the places stand for, as the injected file reads it (XML entities as written). */
  fun text(places: List<Place>): String = places.joinToString("") { it.prefix + it.range.substring(it.host.text) + it.suffix }

  /**
   * The statement [tag] as MyBatis builds it for one call, to run: the `<if>` parts and `<choose>` branches of
   * [choices] (all parts and first branches by default), `<where>` / `<set>` / `<trim>` with MyBatis' rules, one
   * `<foreach>` item, `#{a.b}` as `:a_b`.
   */
  fun runnable(tag: XmlTag, choices: Choices = Choices()): String = tidy(Runner(choices).render(tag, 0))

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
  }.let(::tidy)

  private fun tidy(sql: String): String = sql.lines().joinToString("\n") { it.trimEnd() }.trimIndent().trim().replace(Regex("\n{3,}"), "\n\n")

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

  /** Every `<include>` in [tag] (and in what they include) names a `<sql>` that is found. */
  fun includesFound(tag: XmlTag, depth: Int = 0): Boolean = depth <= 8 &&
    com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(tag, XmlTag::class.java).filter { it.name == "include" }
      .all { includeTarget(it)?.let { t -> includesFound(t, depth + 1) } == true }

  fun isWhole(annotation: PsiAnnotation): Boolean =
    annotation.qualifiedName in MyBatis.SQL_ANNOTATIONS && annotation.findDeclaredAttributeValue("value")?.text?.contains("\${") == false

  /** The `<sql>` an `<include refid>` names, here or in another mapper. */
  fun includeTarget(include: XmlTag): XmlTag? {
    // not through the refid's references: computing those asks for injections, which come here
    val id = include.getAttributeValue("refid") ?: return null
    return MyBatis.findTag(include, "sql", id)
  }

  private class Runner(val choices: Choices) {
    private val HASH = Regex("#\\{[^}]*}")
    private val WHERE_OVERRIDES = listOf("AND ", "OR ", "AND\n", "OR\n", "AND\r", "OR\r", "AND\t", "OR\t")

    fun render(tag: XmlTag, depth: Int): String {
      val out = StringBuilder()
      for (child in tag.value.children) {
        val part = when (child) {
          is XmlText -> HASH.replace(StringUtil.unescapeXmlEntities(child.text.replace("<![CDATA[", "").replace("]]>", ""))) { runnableHole(it.value) }
          is XmlTag -> element(child, depth)
          else -> null
        } ?: continue
        // MyBatis joins the parts of a dynamic statement with spaces
        if (out.isNotEmpty() && part.isNotEmpty() && !out.last().isWhitespace() && !part.first().isWhitespace()) out.append(' ')
        out.append(part)
      }
      return out.toString()
    }

    private fun element(tag: XmlTag, depth: Int): String? = when (tag.name) {
      "if", "when", "otherwise" -> if (tag in choices.off) null else render(tag, depth)
      "choose" -> (choices.branch[tag] ?: branches(tag).firstOrNull())?.let { render(it, depth) }
      "where" -> trim(render(tag, depth), "WHERE", "", WHERE_OVERRIDES, emptyList())
      "set" -> trim(render(tag, depth), "SET", "", listOf(","), listOf(","))
      "trim" -> trim(render(tag, depth), tag.getAttributeValue("prefix").orEmpty(), tag.getAttributeValue("suffix").orEmpty(),
                     overrides(tag.getAttributeValue("prefixOverrides")), overrides(tag.getAttributeValue("suffixOverrides")))
      "foreach" -> tag.getAttributeValue("open").orEmpty() + render(tag, depth).trim() + tag.getAttributeValue("close").orEmpty()
      "include" -> includeTarget(tag)?.takeIf { depth <= 8 }?.let { render(it, depth + 1) }
      "bind", "selectKey" -> null
      else -> render(tag, depth)
    }

    /** MyBatis' TrimSqlNode: nothing for no content, else the overrides dropped (case-insensitive) and prefix / suffix added. */
    private fun trim(content: String, prefix: String, suffix: String, prefixOverrides: List<String>, suffixOverrides: List<String>): String? {
      var sql = content.trim()
      if (sql.isEmpty()) return null
      prefixOverrides.firstOrNull { sql.uppercase().startsWith(it.uppercase()) }?.let { sql = sql.substring(it.length).trimStart() }
      suffixOverrides.firstOrNull { sql.uppercase().endsWith(it.uppercase()) }?.let { sql = sql.substring(0, sql.length - it.length).trimEnd() }
      return " $prefix $sql $suffix "
    }

    /** MyBatis splits overrides on `|` and keeps their spaces. */
    private fun overrides(attribute: String?): List<String> = attribute?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
  }

  private class Builder(val branch: Map<XmlTag, XmlTag>, val keepOverrides: Boolean, val depth: Int) {
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
          val inner = Builder(emptyMap(), false, depth + 1).also { it.walk(target) }.result
          result += Extra(" " + MapperSql.text(places(inner)) + " ")
        }
        "choose" -> {
          val chosen = branch[tag]
          if (chosen != null) element(chosen) else walk(tag)
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
        if (hole != null) result += Hole(hole, host)
        start = cut.endOffset
      }
      if (range.endOffset > start) result += Text(host, TextRange(start, range.endOffset))
    }

    private fun overrides(attribute: String?): List<String> = attribute?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun dropPrefix(tag: XmlTag, words: List<String>) {
      if (keepOverrides) return
      val text = firstText(tag) ?: return
      val raw = text.text
      val at = raw.indexOfFirst { !it.isWhitespace() }
      val word = words.firstOrNull { raw.regionMatches(at, it, 0, it.length, ignoreCase = true) } ?: return
      dropped.getOrPut(text) { ArrayList() } += TextRange(at, at + word.length)
    }

    private fun dropSuffix(tag: XmlTag, words: List<String>) {
      if (keepOverrides) return
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
