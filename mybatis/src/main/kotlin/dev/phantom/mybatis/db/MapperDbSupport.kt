// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis.db

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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import dev.phantom.db.core.SqlLanguage
import dev.phantom.db.core.SqlWholeInjection
import dev.phantom.db.core.exec.DbRunner
import dev.phantom.mybatis.MapperSql
import dev.phantom.mybatis.MyBatis
import dev.phantom.mybatis.PhantomMyBatisBundle
import javax.swing.Icon

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
 * A run icon on each mapper statement and MyBatis SQL annotation: the statement runs in Phantom Database Console,
 * which asks for the values of `#{...}` (as `:name`) and `${...}`. `<if>` parts are all kept, `<choose>` takes its
 * first branch.
 */
class MapperRunLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomMyBatisBundle.message("run.gutter.name")
  override fun getIcon(): Icon = AllIcons.Actions.Execute

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (statementTag(element) == null && sqlAnnotation(element) == null) return null
    val handler = GutterIconNavigationHandler<PsiElement> { event, e ->
      val chunks = statementTag(e)?.let { MapperSql.chunks(it, MapperSql.Mode.RUN) } ?: sqlAnnotation(e)?.let { MapperSql.chunks(it) }
      val text = chunks?.let { MapperSql.runnable(it) }?.takeIf { it.isNotBlank() } ?: return@GutterIconNavigationHandler
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

/**
 * Reformat Code on a mapper XML file also formats the SQL of statements and `<sql>` fragments that are plain text
 * (no dynamic tags), with Phantom Database Console's SQL code style, indented one level inside the tag.
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
    if (tag.subTags.isNotEmpty()) return 0
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
