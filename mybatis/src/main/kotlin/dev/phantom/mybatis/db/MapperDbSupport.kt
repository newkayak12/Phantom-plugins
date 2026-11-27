// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis.db

import dev.phantom.mybatis.GhostMode
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.lang.xml.XMLLanguage
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.SqlLanguage
import dev.phantom.db.core.SqlSyntaxProblems
import dev.phantom.db.core.SqlWholeInjection
import dev.phantom.db.core.exec.DbRunner
import dev.phantom.mybatis.MapperSql
import dev.phantom.mybatis.MyBatis
import dev.phantom.mybatis.PhantomMyBatisBundle
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** Mapper statements that read as one whole statement get Phantom Database Console's syntax errors. */
class MapperWholeInjection : SqlWholeInjection {
  override fun isWhole(file: PsiFile): Boolean {
    val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return false
    if (host is XmlText) {
      var tag = host.parentTag
      while (tag != null && tag.parentTag?.name != "mapper") tag = tag.parentTag
      return tag != null && MyBatis.mapperOf(tag) != null && MapperSql.isWhole(tag)
    }
    val annotation = PsiTreeUtil.getParentOfType(host, PsiAnnotation::class.java) ?: return false
    return MapperSql.isWhole(annotation)
  }
}

/**
 * Syntax errors in mapper statements that do not read as one whole statement: each branch of a `<choose>` is checked
 * on its own, and `${...}` reads as a name. Errors in the text the tags add or in `<include>`d fragments are left out.
 */
// Not DumbAware: `<include>` targets in other mappers are found through an index.
class MapperSqlSyntaxAnnotator : Annotator {
  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    val tag = element as? XmlTag ?: return
    if (tag.name !in MyBatis.STATEMENTS || MyBatis.mapperOf(tag)?.let { tag.parentTag == it } != true) return
    for ((range, message) in problems(tag)) holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
  }

  /**
   * Cached per tag until an XML file changes: each `<choose>` branch is parsed on its own, up to 32 parses, which
   * otherwise ran again on every highlighting pass. Any XML change counts, as `<include>`d fragments may be in other files.
   */
  private fun problems(tag: XmlTag): List<Pair<TextRange, String>> = CachedValuesManager.getCachedValue(tag) {
    CachedValueProvider.Result.create(computeProblems(tag), PsiModificationTracker.getInstance(tag.project).forLanguages { it.id == "XML" })
  }

  private fun computeProblems(tag: XmlTag): List<Pair<TextRange, String>> {
    if (MapperSql.isWhole(tag) || !MapperSql.includesFound(tag)) return emptyList()
    val seen = LinkedHashSet<Pair<TextRange, String>>()
    for (variant in MapperSql.variants(tag)) {
      val (sql, segments) = text(variant)
      for (problem in SqlSyntaxProblems.of(sql)) {
        val segment = segments.firstOrNull { problem.range.startOffset >= it.first && problem.range.startOffset < it.first + it.second.range.length } ?: continue
        val chunk = segment.second
        val start = chunk.host.textRange.startOffset + chunk.range.startOffset + problem.range.startOffset - segment.first
        val end = minOf(start + problem.range.length, chunk.host.textRange.startOffset + chunk.range.endOffset)
        seen += TextRange(start, end) to problem.message
      }
    }
    return seen.toList()
  }

  /** The SQL of [chunks] with `#{}` as `?` and `${}` as a name, and where each text chunk starts in it. */
  private fun text(chunks: List<MapperSql.Chunk>): Pair<String, List<Pair<Int, MapperSql.Text>>> {
    val sql = StringBuilder()
    val segments = ArrayList<Pair<Int, MapperSql.Text>>()
    for (c in chunks) when (c) {
      is MapperSql.Text -> { segments += sql.length to c; sql.append(c.range.substring(c.host.text)) }
      is MapperSql.Hole -> sql.append(if (c.text.startsWith("$")) "x" else "?")
      is MapperSql.Extra -> sql.append(c.text)
    }
    return sql.toString() to segments
  }
}

/**
 * A run icon on each mapper statement and MyBatis SQL annotation: the statement runs in Phantom Database Console,
 * which asks for the values of `#{...}` (as `:name`) and `${...}`. A statement with `<if>` or `<choose>` first asks
 * which parts to run.
 */
class MapperRunLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomMyBatisBundle.message("run.gutter.name")
  override fun getIcon(): Icon = AllIcons.Actions.Execute

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (statementTag(element) == null && sqlAnnotation(element) == null) return null
    val handler = GutterIconNavigationHandler<PsiElement> { event, e ->
      val tag = statementTag(e)
      val text = if (tag != null) {
        val choices = MapperRunDialog.ask(tag) ?: return@GutterIconNavigationHandler
        MapperSql.runnable(tag, choices)
      }
      else sqlAnnotation(e)?.let { MapperSql.chunks(it) }?.let { MapperSql.runnable(it) }
      if (text.isNullOrBlank()) return@GutterIconNavigationHandler
      val file = e.containingFile?.virtualFile ?: return@GutterIconNavigationHandler
      DbRunner.run(e.project, file, listOf(text), event?.component)
    }
    return LineMarkerInfo(element, element.textRange, AllIcons.Actions.Execute, { PhantomMyBatisBundle.message("run.gutter.tooltip") },
                          handler, GutterIconRenderer.Alignment.LEFT, { PhantomMyBatisBundle.message("run.gutter.tooltip") })
  }

  companion object {
    /** The statement whose start tag name [element] is. */
    fun statementTag(element: PsiElement): XmlTag? {
      if (element.node?.elementType != XmlTokenType.XML_NAME) return null
      val tag = element.parent as? XmlTag ?: return null
      if (tag.children.firstOrNull { it.node.elementType == XmlTokenType.XML_NAME } != element) return null
      val root = MyBatis.mapperOf(tag) ?: return null
      return tag.takeIf { it.name in MyBatis.STATEMENTS && it.parentTag == root }
    }

    /** The MyBatis SQL annotation whose name [element] is. */
    fun sqlAnnotation(element: PsiElement): PsiAnnotation? {
      if (element !is PsiIdentifier) return null
      val annotation = (element.parent as? PsiJavaCodeReferenceElement)?.parent as? PsiAnnotation ?: return null
      return annotation.takeIf { it.qualifiedName in MyBatis.SQL_ANNOTATIONS }
    }
  }
}

/** Which `<if>` parts and `<choose>` branches a run takes. */
class MapperRunDialog private constructor(tag: XmlTag, private val ifs: List<XmlTag>, private val chooses: List<XmlTag>) :
  DialogWrapper(tag.project) {
  private val boxes = ifs.associateWith { JBCheckBox(label(it), true) }
  private val combos = chooses.associateWith { c -> ComboBox(MapperSql.branches(c).map { label(it) }.toTypedArray()) }

  init {
    title = PhantomMyBatisBundle.message("run.dialog.title", tag.getAttributeValue("id").orEmpty())
    init()
  }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(VerticalFlowLayout(0, 4))
    panel.add(JBLabel(PhantomMyBatisBundle.message("run.dialog.hint")))
    for (t in (ifs + chooses).sortedBy { it.textRange.startOffset }) {
      val indent = generateSequence(t.parentTag) { it.parentTag }.count { it.name in DYNAMIC } * 16
      val row = boxes[t] ?: JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
        add(JBLabel("<choose>")); add(combos.getValue(t))
      }
      row.border = JBUI.Borders.emptyLeft(indent)
      panel.add(row)
    }
    return JBScrollPane(panel).apply { border = null; preferredSize = JBUI.size(560, minOf(420, 60 + 30 * (ifs.size + chooses.size))) }
  }

  private fun choices() = MapperSql.Choices(
    off = boxes.filterValues { !it.isSelected }.keys,
    branch = combos.mapValues { (c, box) -> MapperSql.branches(c)[box.selectedIndex.coerceAtLeast(0)] })

  companion object {
    private val DYNAMIC = setOf("if", "when", "otherwise", "choose", "where", "set", "trim", "foreach")

    /** The parts to run: asked when [tag] has `<if>` or `<choose>` (also in its `<include>`s); null when cancelled. */
    fun ask(tag: XmlTag): MapperSql.Choices? {
      val all = ArrayList<XmlTag>()
      fun collect(t: XmlTag, depth: Int) {
        for (child in t.subTags) {
          if (child.name == "include") MapperSql.includeTarget(child)?.takeIf { depth < 8 }?.let { collect(it, depth + 1) }
          else { all += child; collect(child, depth) }
        }
      }
      collect(tag, 0)
      val ifs = all.filter { it.name == "if" || (it.name == "when" && it.parentTag?.name != "choose") }
      val chooses = all.filter { it.name == "choose" && MapperSql.branches(it).size > 1 }
      if (ifs.isEmpty() && chooses.isEmpty() || ApplicationManager.getApplication().isUnitTestMode) return MapperSql.Choices()
      val dialog = MapperRunDialog(tag, ifs, chooses)
      return if (dialog.showAndGet()) dialog.choices() else null
    }

    private fun label(t: XmlTag): String {
      val sql = t.value.textElements.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(57) + "..." else it }
      val head = if (t.name == "otherwise") "otherwise" else "${t.name} ${t.getAttributeValue("test").orEmpty()}"
      return if (sql.isEmpty()) head else "$head  →  $sql"
    }
  }
}

/**
 * Reformat Code on a mapper XML file also formats the SQL of statements and `<sql>` fragments with Phantom Database
 * Console's SQL code style, indented one level inside the tag. With dynamic tags each tag goes on its own line and the
 * text inside it one level deeper; statements with XML comments or CDATA next to tags are left alone.
 */
class MapperSqlPostFormatProcessor : PostFormatProcessor {
  override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
    format(source, source.textRange, settings)
    return source
  }

  override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
    val delta = format(source, rangeToReformat, settings)
    return TextRange(rangeToReformat.startOffset, rangeToReformat.endOffset + delta)
  }

  /** Formats the statements inside [range]; returns how much longer the text got. */
  private fun format(source: PsiElement, range: TextRange, settings: CodeStyleSettings): Int {
    val root = MyBatis.mapperRoot(source.containingFile) ?: return 0
    var delta = 0
    // from the end, so the offsets of the tags before stay valid
    for (tag in root.subTags.filter { it.name in MyBatis.STATEMENTS || it.name == "sql" }.reversed()) {
      if (!range.intersects(tag.textRange)) continue
      delta += MapperSqlFormat.format(tag, settings)
    }
    return delta
  }
}

object MapperSqlFormat {
  private val HOLE = Regex("[#$]\\{[^}\\n]*}")

  /** Formats the SQL of [tag] when it is one plain text (or one CDATA section); returns the change in length. */
  fun format(tag: XmlTag, settings: CodeStyleSettings): Int {
    if (tag.subTags.isNotEmpty()) return formatDynamic(tag, settings)
    val texts = tag.value.textElements
    if (texts.size != 1) return 0
    val raw = tag.value.text
    val trimmed = raw.trim()
    val cdata = trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")
    val body = if (cdata) trimmed.removePrefix("<![CDATA[").removeSuffix("]]>") else StringUtil.unescapeXmlEntities(raw)
    if (body.isBlank() || body.contains("<![CDATA[") || body.contains('?')) return 0
    val holes = HOLE.findAll(body).map { it.value }.toList()
    val sql = HOLE.replace(body, "?")
    val file = PsiFileFactory.getInstance(tag.project).createFileFromText("mapper.sql", SqlLanguage, sql)
    CodeStyleManager.getInstance(tag.project).reformat(file)
    val formatted = restore(file.text.trim(), holes) ?: return 0
    val tagIndent = indentOf(tag)
    val options = settings.getIndentOptions(XmlFileType.INSTANCE)
    val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    val inner = formatted.lines().joinToString("\n") { if (it.isBlank()) "" else tagIndent + unit + it }
    val escaped = if (cdata) "<![CDATA[\n$inner\n$tagIndent]]>" else "\n" + inner.replace("&", "&amp;").replace("<", "&lt;") + "\n" + tagIndent
    if (escaped == raw) return 0
    tag.value.setEscapedText(escaped)
    return escaped.length - raw.length
  }

  private val MARK = Regex("/\\*(@\\d+|\\{|})\\*/")
  private val HOLE_MARK = Regex("\\?\\s*/\\*#(\\d+)\\*/")
  private val OPAQUE = setOf("include", "bind", "selectKey")

  /**
   * A statement with dynamic tags: the SQL of all its texts (and what the tags add, for context) is formatted as one,
   * with marks where each text starts; each text then goes back with its new line breaks and keyword case, indented by
   * how deep its tag is. Nothing changes unless every text reads the same apart from whitespace and case.
   */
  private fun formatDynamic(tag: XmlTag, settings: CodeStyleSettings): Int {
    if (PsiTreeUtil.findChildOfType(tag, XmlComment::class.java) != null || tag.value.text.contains("<![CDATA[")) return 0
    val chunks = MapperSql.chunks(tag, keepOverrides = true) ?: return 0
    val hosts = ArrayList<XmlText>()
    val holes = ArrayList<String>()
    val sql = StringBuilder()
    fun enter(host: PsiElement?) {
      val text = host as? XmlText ?: return
      if (hosts.lastOrNull() !== text) { sql.append("/*@${hosts.size}*/"); hosts += text }
    }
    for (c in chunks) when (c) {
      is MapperSql.Text -> { enter(c.host); sql.append(StringUtil.unescapeXmlEntities(c.range.substring(c.host.text))) }
      is MapperSql.Hole -> { enter(c.host); sql.append("?/*#${holes.size}*/"); holes += c.text }
      is MapperSql.Extra -> sql.append("/*{*/").append(c.text).append("/*}*/")
    }
    if (holes.any { it.contains("*/") }) return 0
    val file = PsiFileFactory.getInstance(tag.project).createFileFromText("mapper.sql", SqlLanguage, sql.toString())
    CodeStyleManager.getInstance(tag.project).reformat(file)
    var formatted = file.text
    if (HOLE_MARK.findAll(formatted).count() != holes.size) return 0
    formatted = HOLE_MARK.replace(formatted) { holes[it.groupValues[1].toInt()] }
    // the text of each host, and whether it starts a line
    val pieces = HashMap<XmlText, Pair<String, Boolean>>()
    var current: XmlText? = null
    var extra = false
    var at = 0
    fun flush(end: Int) {
      val piece = formatted.substring(at, end)
      val host = current
      if (host != null && !extra) {
        val lineStart = formatted.lastIndexOf('\n', at - 1) + 1
        val startsLine = MARK.replace(formatted.substring(lineStart, at), "").isBlank()
        pieces[host] = piece to startsLine
      }
      else if (piece.isNotBlank() && !extra) current = null
    }
    for (m in MARK.findAll(formatted)) {
      flush(m.range.first)
      when (val mark = m.groupValues[1]) {
        "{" -> extra = true
        "}" -> { extra = false; current = null }
        else -> current = hosts[mark.substring(1).toInt()]
      }
      at = m.range.last + 1
    }
    flush(formatted.length)
    val restored = HashMap<XmlText, String>()
    for (host in hosts) {
      val (piece, startsLine) = pieces[host] ?: return 0
      val original = HOLE_MARK.replace(StringUtil.unescapeXmlEntities(host.text), "")
      if (squeeze(piece) != squeeze(original)) return 0
      restored[host] = reindent(piece, startsLine)
    }
    val options = settings.getIndentOptions(XmlFileType.INSTANCE)
    val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    val raw = tag.value.text
    val inner = inner(tag, indentOf(tag), unit, restored)
    if (inner == raw) return 0
    // setEscapedText cannot take tags: the whole tag is replaced
    val start = tag.value.textRange.startOffset - tag.textRange.startOffset
    val end = tag.value.textRange.endOffset - tag.textRange.startOffset
    val text = tag.text.substring(0, start) + inner + tag.text.substring(end)
    tag.replace(XmlElementFactory.getInstance(tag.project).createTagFromText(text, XMLLanguage.INSTANCE))
    return inner.length - raw.length
  }

  private fun squeeze(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

  /** The lines of a piece without the indent they share; a piece that starts mid-line starts at that indent. */
  private fun reindent(piece: String, startsLine: Boolean): String {
    val lines = piece.lines()
    val indents = lines.withIndex().filter { (i, l) -> l.isNotBlank() && (i > 0 || startsLine) }.map { (_, l) -> l.takeWhile { it == ' ' || it == '\t' }.length }
    val common = indents.minOrNull() ?: 0
    return lines.withIndex().filter { it.value.isNotBlank() }.joinToString("\n") { (i, l) ->
      if (i == 0 && !startsLine) l.trim() else l.drop(common).trimEnd()
    }
  }

  /** The new text inside [container]: tags each on a line at [indent] + [unit], texts one level inside their tag. */
  private fun inner(container: XmlTag, indent: String, unit: String, restored: Map<XmlText, String>): String {
    val out = StringBuilder()
    val childIndent = indent + unit
    for (child in container.value.children) {
      when (child) {
        is XmlText -> {
          val text = restored[child] ?: StringUtil.unescapeXmlEntities(child.text).trimIndent()
          for (line in text.lines()) if (line.isNotBlank()) out.append('\n').append(childIndent).append(line.trimEnd().replace("&", "&amp;").replace("<", "&lt;"))
        }
        is XmlTag -> {
          out.append('\n').append(childIndent)
          val start = child.value.textRange.startOffset - child.textRange.startOffset
          val end = child.value.textRange.endOffset - child.textRange.startOffset
          if (child.name in OPAQUE || child.isEmpty || child.value.children.isEmpty()) out.append(child.text)
          else out.append(child.text, 0, start).append(inner(child, childIndent, unit, restored)).append(child.text, end, child.textLength)
        }
      }
    }
    return out.append('\n').append(indent).toString()
  }

  /** `?` back to the holes, in order; null when the count differs (a `?` inside a string). */
  private fun restore(sql: String, holes: List<String>): String? {
    val result = StringBuilder()
    var i = 0
    var quote: Char? = null
    for (c in sql) {
      if (quote != null) { if (c == quote) quote = null; result.append(c); continue }
      when (c) {
        '\'', '"', '`' -> { quote = c; result.append(c) }
        '?' -> { if (i >= holes.size) return null; result.append(holes[i++]) }
        else -> result.append(c)
      }
    }
    return if (i == holes.size) result.toString() else null
  }

  private fun indentOf(tag: XmlTag): String {
    val text = tag.containingFile.text
    val start = tag.textRange.startOffset
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    return text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
  }
}
