// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlFile

/** EXPLAIN in each database's own form; ANALYZE runs the statement and reports the real plan. */
object DbExplain {
  /** `null` for a database without a dialect. */
  fun sql(dbms: Dbms, statement: String, analyze: Boolean): String? {
    val body = statement.trim().removeSuffix(";").trim()
    return when (dbms) {
      Dbms.MYSQL -> if (analyze) "EXPLAIN ANALYZE $body" else "EXPLAIN $body"
      // MariaDB spells it ANALYZE <statement>
      Dbms.MARIADB -> if (analyze) "ANALYZE $body" else "EXPLAIN $body"
      Dbms.POSTGRESQL -> if (analyze) "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $body" else "EXPLAIN (FORMAT TEXT) $body"
      Dbms.H2 -> if (analyze) "EXPLAIN ANALYZE $body" else "EXPLAIN $body"
      Dbms.GENERIC -> null
    }
  }
}

/** Explain Plan / Explain Analyze for the selection or the statement at the caret; the plan shows in DB Results. */
abstract class DbExplainAction(private val analyze: Boolean) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.PSI_FILE) as? SqlFile
    val project = e.project
    val dialect = project == null || file?.virtualFile?.let { DbConsoles.dataSourceFor(project, it) }?.dbms?.hasDialect != false
    e.presentation.isEnabledAndVisible = file != null && dialect && statement(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile ?: return
    val statement = statement(e) ?: return
    DbRunner.run(project, file, listOf(statement), e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) { dataSource ->
      if (analyze && SqlSafety.changesData(statement)) {
        if (dataSource.readOnly) {
          Messages.showErrorDialog(project, PhantomDbBundle.message("db.safety.read.only", dataSource.name, SqlSafety.preview(statement)),
                                   PhantomDbBundle.message("action.PhantomDb.ExplainAnalyze.text"))
          return@run null
        }
        val ok = Messages.showOkCancelDialog(project, PhantomDbBundle.message("db.explain.analyze.changes", SqlSafety.preview(statement)),
                                             PhantomDbBundle.message("action.PhantomDb.ExplainAnalyze.text"),
                                             PhantomDbBundle.message("db.safety.run"), Messages.getCancelButton(), Messages.getWarningIcon())
        if (ok != Messages.OK) return@run null
      }
      DbExplain.sql(dataSource.dbms, statement, analyze)?.let { listOf(it) }
    }
  }

  private fun statement(e: AnActionEvent): String? {
    val file = e.getData(CommonDataKeys.PSI_FILE) as? SqlFile ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val selection = editor.selectionModel
    if (selection.hasSelection()) return dev.phantom.db.core.SqlStatements.split(selection.selectedText.orEmpty()).firstOrNull()
    return file.statementAt(editor.caretModel.offset)?.executableText?.takeIf { it.isNotEmpty() }
  }
}

class DbExplainPlanAction : DbExplainAction(analyze = false)

class DbExplainAnalyzeAction : DbExplainAction(analyze = true)
