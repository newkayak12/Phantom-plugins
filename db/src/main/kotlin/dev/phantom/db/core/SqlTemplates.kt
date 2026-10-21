// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.phantom.db.core.exec.DbNames

/** Where the "SQL (Phantom)" live templates apply: .sql files, consoles, and SQL injected into Java and Kotlin strings. */
class SqlTemplateContextType : TemplateContextType(PhantomDbBundle.message("sql.template.context")) {
  override fun isInContext(context: TemplateActionContext): Boolean = sqlFile(context.file, context.startOffset) != null

  companion object {
    /** [file] if it is SQL, else the SQL injected at [offset]. */
    fun sqlFile(file: PsiFile, offset: Int): SqlFile? {
      if (file is SqlFile) return file
      return InjectedLanguageManager.getInstance(file.project).findInjectedElementAt(file, offset)?.containingFile as? SqlFile
    }
  }
}

/**
 * `phantomSqlColumns(table)`: the columns of the table, comma-separated, when they are cached; empty otherwise (the
 * load is started, never waited for), so the template still expands at once.
 */
class SqlColumnsMacro : MacroBase("phantomSqlColumns", "phantomSqlColumns(table)") {
  override fun calculateResult(params: Array<out Expression>, context: ExpressionContext, quick: Boolean): Result? {
    val name = params.firstOrNull()?.calculateResult(context)?.toString()?.trim()?.ifEmpty { null } ?: return null
    val project = context.project
    val editor = context.editor ?: return null
    val host = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
    val file = SqlTemplateContextType.sqlFile(host, context.startOffset) ?: return null
    val dataSource = SqlSchema.dataSourceFor(file, strict = false) ?: return null
    val schema = SqlSchema.getInstance(project)
    val snapshot = schema.snapshotFor(file, dataSource, load = false) ?: return null
    val parts = name.split('.').map(SqlNames::unquote)
    val table = snapshot.find(parts.last(), parts.dropLast(1).lastOrNull()).firstOrNull() ?: return null
    val columns = schema.columnsNoWait(dataSource, table) ?: return null
    return TextResult(columns.joinToString(", ") { DbNames.identifier(dataSource.dialect(), it.name) })
  }
}
