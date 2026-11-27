// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.io.IOException
import javax.swing.JComponent

private const val BUNDLE = "messages.VersionCatalogBundle"

object VersionCatalogBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Asks Maven Central for newer library versions of the open `*.versions.toml` and offers to write them into the file. */
class CheckNewerVersionsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabledAndVisible = e.project != null && file != null && file.name.endsWith(".versions.toml")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val coordinates = VersionCatalogParser.parse(document.text).libraries.map { it.group to it.artifact }.distinct()
    val title = VersionCatalogBundle.message("dialog.title")
    if (coordinates.isEmpty()) {
      Messages.showInfoMessage(project, VersionCatalogBundle.message("message.no.libraries"), title)
      return
    }
    object : Task.Backgroundable(project, VersionCatalogBundle.message("progress.title"), true) {
      private val available = HashMap<String, List<String>>()
      private var failed = 0

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        coordinates.forEachIndexed { index, (group, artifact) ->
          indicator.checkCanceled()
          indicator.fraction = index.toDouble() / coordinates.size
          indicator.text2 = "$group:$artifact"
          try {
            MavenCentral.fetchVersions(group, artifact, indicator)?.let { available["$group:$artifact"] = it }
          } catch (e: IOException) {
            thisLogger().info("Could not read versions of $group:$artifact", e)
            failed++
          }
        }
      }

      override fun onSuccess() = showUpdates(project, document, available, failed)
    }.queue()
  }

  private fun showUpdates(project: Project, document: Document, available: Map<String, List<String>>, failed: Int) {
    val title = VersionCatalogBundle.message("dialog.title")
    // The file may have been edited while Maven Central answered, so the offsets come from its current text.
    val updates = VersionUpdates.compute(VersionCatalogParser.parse(document.text), available)
    if (updates.isEmpty()) {
      val message = if (failed > 0) "message.up.to.date.failed" else "message.up.to.date"
      Messages.showInfoMessage(project, VersionCatalogBundle.message(message, failed), title)
      return
    }
    val dialog = UpdatesDialog(project, updates, failed)
    if (!dialog.showAndGet()) return
    val chosen = dialog.chosen()
    if (chosen.isEmpty()) return
    WriteCommandAction.runWriteCommandAction(project, VersionCatalogBundle.message("command.name"), null, {
      for (update in chosen.sortedByDescending { it.literal.start }) {
        document.replaceString(update.literal.start, update.literal.end, update.newVersion)
      }
    })
  }

  private class UpdatesDialog(project: Project, private val updates: List<VersionUpdate>, failed: Int) : DialogWrapper(project) {
    private val list = CheckBoxList<VersionUpdate>()

    init {
      title = VersionCatalogBundle.message("dialog.title")
      for (update in updates) {
        val key = if (update.isVersionRef) "dialog.item.ref" else "dialog.item.library"
        list.addItem(update, VersionCatalogBundle.message(key, update.name, update.literal.value, update.newVersion), true)
      }
      if (failed > 0) setErrorText(VersionCatalogBundle.message("dialog.failed", failed))
      init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(list)

    fun chosen(): List<VersionUpdate> = updates.filter { list.isItemSelected(it) }
  }
}
