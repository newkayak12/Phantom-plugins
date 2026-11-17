// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.SqlStatements

/** "Run 'x.sql'" in the Project view and the editor tab menu: runs the whole file like Run Statement runs a selection. */
class DbRunSqlFileAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = sqlFile(e)
    e.presentation.isEnabledAndVisible = file != null && e.project != null
    if (file != null) e.presentation.setText(PhantomDbBundle.message("db.run.file", file.name), false)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = sqlFile(e) ?: return
    val document = FileDocumentManager.getInstance().getDocument(file)
    val text = document?.text ?: VfsUtilCore.loadText(file)
    DbRunner.run(project, file, SqlStatements.split(text), e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
  }

  /** The one selected `.sql` file, from the editor tab or the Project view. */
  private fun sqlFile(e: AnActionEvent): VirtualFile? {
    val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
    val file = if (files != null && files.size > 1) null else e.getData(CommonDataKeys.VIRTUAL_FILE) ?: files?.singleOrNull()
    return file?.takeIf { !it.isDirectory && it.extension.equals("sql", true) }
  }
}
