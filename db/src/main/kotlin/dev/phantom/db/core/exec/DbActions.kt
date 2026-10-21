// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.execution.Location
import dev.phantom.db.core.dialect
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.phantom.db.core.*
import dev.phantom.db.core.PhantomDbIcons
import java.util.function.Function
import javax.swing.JComponent

/** ▶ on the first token of each statement, once the project has a data source. */
class SqlRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.firstChild != null) return null
    val statement = element.parent as? SqlStatement ?: return null
    if (statement.firstChild != element || statement.executableText.isEmpty()) return null
    if (DbDataSourceService.getInstance(element.project).dataSources.isEmpty()) return null
    val action = ActionManager.getInstance().getAction(RUN_ACTION_ID) ?: return null
    return Info(AllIcons.Actions.Execute, arrayOf(action)) { PhantomDbBundle.message("db.action.run.text") }
  }

  override fun isDumbAware(): Boolean = true

  companion object {
    const val RUN_ACTION_ID: String = "PhantomDb.RunStatement"
  }
}

/** Runs the selection (split into statements), else the statement at the gutter icon or caret. */
class SqlRunStatementAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PSI_FILE) is SqlFile && statements(e).isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile ?: return
    DbRunner.run(project, file, statements(e), e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
  }

  private fun statements(e: AnActionEvent): List<String> {
    val file = e.getData(CommonDataKeys.PSI_FILE) as? SqlFile ?: return emptyList()
    e.getData(Location.DATA_KEY)?.psiElement?.let { element ->
      return listOfNotNull(PsiTreeUtil.getParentOfType(element, SqlStatement::class.java, false)?.executableText?.takeIf { it.isNotEmpty() })
    }
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return emptyList()
    val selection = editor.selectionModel
    if (selection.hasSelection()) return SqlStatements.split(selection.selectedText.orEmpty())
    return listOfNotNull(file.statementAt(editor.caretModel.offset)?.executableText?.takeIf { it.isNotEmpty() })
  }
}

class SqlSelectDataSourceAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile
    val project = e.project
    e.presentation.isEnabledAndVisible = file != null && project != null && !DbConsoles.isConsole(file) &&
                                         DbDataSourceService.getInstance(project).dataSources.isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile ?: return
    DbRunner.chooseDataSource(project, file, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) {}
  }
}

/** The schema a `.sql` file or console runs in and resolves unqualified names against. */
class SqlSelectSchemaAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile
    val project = e.project
    e.presentation.isEnabledAndVisible = file != null && project != null && DbConsoles.dataSourceFor(project, file) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = (e.getData(CommonDataKeys.PSI_FILE) as? SqlFile)?.virtualFile ?: return
    DbRunner.chooseSchema(project, file, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
  }
}

/** In a `.sql` editor, the run shortcut wins over other actions bound to the same keys. */
class SqlActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (context.getData(CommonDataKeys.PSI_FILE) !is SqlFile) return emptyList()
    return actions.filterIsInstance<SqlRunStatementAction>()
  }
}

/**
 * One line above a `.sql` file once the project has data sources: which one Ctrl+Enter runs on (with a link to change
 * it; consoles are tied to theirs), the schema it runs in (a link to choose another), and the commit mode: Auto-commit, or Manual with the open transaction's statement
 * count and Commit / Roll Back links.
 */
class SqlDataSourceNotificationProvider : EditorNotificationProvider {
  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
    if (file.fileType != SqlFileType.INSTANCE) return null
    val service = DbDataSourceService.getInstance(project)
    if (service.dataSources.isEmpty()) return null
    val console = DbConsoles.isConsole(file)
    val selected = DbConsoles.dataSourceFor(project, file)
    if (console && selected == null) return null
    return Function { editor ->
      EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
        val source = PhantomDbBundle.message("db.datasource.current", selected?.name ?: PhantomDbBundle.message("db.datasource.none"))
        val pending = selected?.let { DbPendingChanges.getInstanceIfCreated(project)?.of(it.id)?.size } ?: 0
        val mode = when {
          selected == null -> null
          selected.readOnly -> PhantomDbBundle.message("db.tree.read.only")
          selected.manualCommit -> PhantomDbBundle.message("db.tx.manual", pending)
          else -> PhantomDbBundle.message("db.tx.auto")
        }
        text = listOfNotNull(if (console) null else source, mode).joinToString("  ·  ")
        selected?.let { icon(PhantomDbIcons.forDataSource(it)) }
        if (!console) {
          createActionLabel(PhantomDbBundle.message("db.datasource.change")) {
            DbRunner.chooseDataSource(project, file, this) {}
          }
        }
        if (selected != null) {
          val schema = DbConsoles.schemaFor(project, file) ?: PhantomDbBundle.message("db.schema.default")
          createActionLabel(PhantomDbBundle.message("db.schema.current", schema)) { DbRunner.chooseSchema(project, file, this) }
        }
        if (selected != null && !selected.readOnly) {
          if (selected.manualCommit && pending > 0) {
            createActionLabel(PhantomDbBundle.message("db.changes.commit")) { DbEndTransactionAction.end(project, selected, commit = true) }
            createActionLabel(PhantomDbBundle.message("db.changes.rollback")) { DbEndTransactionAction.end(project, selected, commit = false) }
          }
          createActionLabel(PhantomDbBundle.message(if (selected.manualCommit) "db.tx.to.auto" else "db.tx.to.manual")) {
            DbTransactions.setManual(project, selected, !selected.manualCommit)
          }
        }
      }
    }
  }
}
