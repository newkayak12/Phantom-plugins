// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.TokenType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlLexer
import dev.phantom.db.core.SqlTokens
import javax.swing.JComponent

/**
 * Placeholders in a statement that get a value before it runs: `?`, `:name` and `${name}`, never inside strings,
 * comments or quoted names (the lexer decides). `@var` (MySQL user variables) and PostgreSQL `$1` are left alone.
 *
 * Values are SQL as typed, written into the statement: `42`, `'kim'`, `NULL`, `now()`. They are not bound as JDBC
 * parameters because a typed literal is what makes a comparison work in every dialect (PostgreSQL rejects
 * `bigint = varchar`, which binding a string would produce), and because `${name}` may stand for a name or a clause.
 */
object DbParameters {
  /** One occurrence; [name] is `?1`, `?2`… for positional ones. */
  data class Occurrence(val start: Int, val end: Int, val name: String)

  fun find(sql: String): List<Occurrence> {
    val result = ArrayList<Occurrence>()
    val lexer = SqlLexer()
    lexer.start(sql)
    var positional = 0
    while (lexer.tokenType != null) {
      val type = lexer.tokenType
      val start = lexer.tokenStart
      val end = lexer.tokenEnd
      val text = sql.substring(start, end)
      when {
        type == SqlTokens.PARAMETER && text == "?" -> result += Occurrence(start, end, "?${++positional}")
        type == SqlTokens.PARAMETER && text.startsWith(":") && text.length > 1 -> result += Occurrence(start, end, text.substring(1))
        type !in SqlTokens.COMMENTS && type != SqlTokens.STRING && type != SqlTokens.QUOTED_IDENTIFIER && type != TokenType.WHITE_SPACE -> {
          // `${name}` comes out of the lexer in pieces; find it in the raw text from here
          if (sql.startsWith("\${", start)) {
            val close = sql.indexOf('}', start + 2)
            if (close > start + 2 && sql.substring(start + 2, close).matches(Regex("[A-Za-z_][\\w.]*"))) {
              result += Occurrence(start, close + 1, sql.substring(start + 2, close))
              while (lexer.tokenType != null && lexer.tokenEnd <= close) lexer.advance()
              continue
            }
          }
        }
      }
      lexer.advance()
    }
    return result
  }

  /** Distinct names in order of first use. */
  fun names(statements: List<String>): List<String> {
    var offset = 0
    val names = LinkedHashSet<String>()
    for (sql in statements) {
      // positional numbers run on across statements so each `?` gets its own field
      for (o in find(sql)) names += if (o.name.startsWith("?")) "?" + (o.name.substring(1).toInt() + offset) else o.name
      offset += find(sql).count { it.name.startsWith("?") }
    }
    return names.toList()
  }

  fun substitute(statements: List<String>, values: Map<String, String>): List<String> {
    var offset = 0
    return statements.map { sql ->
      val occurrences = find(sql)
      val text = StringBuilder(sql)
      for (o in occurrences.asReversed()) {
        val key = if (o.name.startsWith("?")) "?" + (o.name.substring(1).toInt() + offset) else o.name
        values[key]?.let { text.replace(o.start, o.end, it) }
      }
      offset += occurrences.count { it.name.startsWith("?") }
      text.toString()
    }
  }

  /** Asks for the values of [names] on the EDT; the last ones typed are offered again. Null when cancelled. */
  fun ask(project: Project, statements: List<String>, names: List<String>): Map<String, String>? {
    val properties = PropertiesComponent.getInstance(project)
    val dialog = Dialog(project, statements, names) { properties.getValue(KEY + it).orEmpty() }
    if (!dialog.showAndGet()) return null
    return dialog.values().onEach { (name, value) -> if (!name.startsWith("?")) properties.setValue(KEY + name, value) }
  }

  private const val KEY = "phantom.db.parameter:"

  private class Dialog(project: Project, statements: List<String>, private val names: List<String>, last: (String) -> String) : DialogWrapper(project) {
    private val fields = names.associateWith { JBTextField(last(it), 30) }
    private val preview = SqlSafety.preview(statements.first())

    init {
      title = PhantomDbBundle.message("db.params.title")
      init()
    }

    fun values(): Map<String, String> = fields.mapValues { it.value.text.trim().ifEmpty { "NULL" } }

    override fun getPreferredFocusedComponent(): JComponent? = fields.values.firstOrNull()

    override fun createCenterPanel(): JComponent {
      val form = FormBuilder.createFormBuilder()
        .addComponent(JBLabel(preview).apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() })
      for (name in names) form.addLabeledComponent(if (name.startsWith("?")) "#${name.substring(1)}:" else "$name:", fields.getValue(name))
      form.addComponent(JBLabel(PhantomDbBundle.message("db.params.hint")).apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(6)
      })
      return form.panel
    }
  }
}
