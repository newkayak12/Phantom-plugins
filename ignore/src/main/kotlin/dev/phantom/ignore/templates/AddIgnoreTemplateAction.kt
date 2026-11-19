// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore.templates

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.JComponent

/** New | Ignore File from Template: appends the chosen templates to an ignore file in the selected directory. */
class AddIgnoreTemplateAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && directory(e)?.fileSystem?.isReadOnly == false
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val dir = directory(e) ?: return
    val dialog = TemplateDialog(project)
    if (!dialog.showAndGet()) return
    val chosen = dialog.templates()
    if (chosen.isEmpty()) return
    val fileName = dialog.fileName()
    val file = addTemplates(project, dir, fileName, chosen.map { it to IgnoreTemplates.load(it) }) ?: return
    FileEditorManager.getInstance(project).openFile(file, true)
  }

  companion object {
    /** Appends [templates] to [fileName] in [dir], creating it; null if the file stays read-only. */
    fun addTemplates(project: Project, dir: VirtualFile, fileName: String, templates: List<Pair<String, String>>): VirtualFile? {
      val existing = dir.findChild(fileName)
      if (existing != null && ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(existing)).hasReadonlyFiles()) {
        return null
      }
      return WriteCommandAction.writeCommandAction(project).withName(PhantomIgnoreBundle.message("action.command")).compute<VirtualFile, Exception> {
        val file = existing ?: dir.createChildData(this, fileName)
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) document.setText(IgnoreTemplates.merge(document.text, templates))
        else file.setBinaryContent(IgnoreTemplates.merge(String(file.contentsToByteArray(), Charsets.UTF_8), templates).toByteArray())
        file
      }
    }
  }

  private fun directory(e: AnActionEvent): VirtualFile? {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.getData(LangDataKeys.IDE_VIEW)?.directories?.firstOrNull()?.virtualFile
    return if (file?.isDirectory == true) file else file?.parent
  }

  private class TemplateDialog(project: Project) : DialogWrapper(project) {
    private val fileName = ComboBox(IgnoreTemplates.FILE_NAMES.toTypedArray())
    private val list = CheckBoxList<String>().apply { IgnoreTemplates.NAMES.forEach { addItem(it, it, false) } }

    init {
      title = PhantomIgnoreBundle.message("dialog.title")
      init()
    }

    fun fileName(): String = fileName.selectedItem as String
    fun templates(): List<String> = IgnoreTemplates.NAMES.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = panel {
      row(PhantomIgnoreBundle.message("dialog.file")) { cell(fileName) }
      row {
        cell(JBScrollPane(list).apply { preferredSize = JBUI.size(320, 300) }).align(Align.FILL)
      }.resizableRow()
    }
  }
}

@NonNls
private const val BUNDLE = "messages.PhantomIgnoreBundle"

object PhantomIgnoreBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
