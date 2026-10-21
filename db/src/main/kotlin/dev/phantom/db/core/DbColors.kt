// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.EmptyIcon
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbResultsToolWindow
import java.awt.Color
import javax.swing.Icon

/** Data source colors (as in DataGrip): palette keys saved with a data source, a faint tint for backgrounds and a stronger stripe. */
object DbColors {
  private class Swatch(val tint: JBColor, val stripe: JBColor)

  private val palette: Map<String, Swatch> = linkedMapOf(
    "red" to Swatch(JBColor(0xFDECEC, 0x3F2C2C), JBColor(0xE05555, 0xC75450)),
    "orange" to Swatch(JBColor(0xFDF1E5, 0x3E3329), JBColor(0xEB8C34, 0xD08A3E)),
    "yellow" to Swatch(JBColor(0xFCF8DF, 0x3B392A), JBColor(0xD9B92B, 0xC2A93A)),
    "green" to Swatch(JBColor(0xECF6E9, 0x2D3A2C), JBColor(0x56A64B, 0x5E9F55)),
    "blue" to Swatch(JBColor(0xEAF1FC, 0x2B3241), JBColor(0x3D7FE0, 0x4A86D0)),
    "purple" to Swatch(JBColor(0xF4EDFB, 0x352D40), JBColor(0x9A62D6, 0x9873C9)),
    "gray" to Swatch(JBColor(0xF1F1F1, 0x353638), JBColor(0x8C8C8C, 0x7A7D80)),
  )

  /** "" (no color) first, then the palette. */
  val keys: List<String> = listOf("") + palette.keys

  /** Editor, tab and tree row background; null for no color or an unknown key. */
  fun tint(key: String?): Color? = palette[key]?.tint

  /** Stripes and swatches. */
  fun stripe(key: String?): Color? = palette[key]?.stripe

  fun displayName(key: String?): String = PhantomDbBundle.message(if (key in palette) "db.color.$key" else "db.color.none")

  fun swatch(key: String?): Icon = stripe(key)?.let { ColorIcon(12, it) } ?: EmptyIcon.create(12)

  /** The tint of the data source a `.sql` file runs on; null for other files. */
  fun tintFor(project: Project, file: VirtualFile): Color? =
    if (file.fileType == SqlFileType.INSTANCE) tint(DbConsoles.dataSourceFor(project, file)?.color) else null
}

/** Colors the tab of a `.sql` file or console with its data source's tint. */
class DbEditorTabColorProvider : EditorTabColorProvider, DumbAware {
  override fun getEditorTabColor(project: Project, file: VirtualFile): Color? = DbColors.tintFor(project, file)
}

/** Gives a `.sql` editor its data source's tint as background, and keeps the service that recolors it later. */
class DbEditorColorListener : EditorFactoryListener {
  override fun editorCreated(event: EditorFactoryEvent) {
    val editor = event.editor
    val project = editor.project ?: return
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
    if (file.fileType != SqlFileType.INSTANCE) return
    DbColorService.getInstance(project).paint(editor, file)
  }
}

/**
 * Recolors `.sql` editors, their tabs and the result tabs when a data source is saved or a file is bound to another
 * one. Created by the first `.sql` editor or result tab, never at startup.
 */
@Service(Service.Level.PROJECT)
class DbColorService(private val project: Project) : Disposable {
  init {
    DbDataSourceService.getInstance(project).addListener(this) {
      ApplicationManager.getApplication().invokeLater({ refresh(null) }, project.disposed)
    }
  }

  /** Repaints [file], or every `.sql` file when null, and the result tabs. On the EDT. */
  fun refresh(file: VirtualFile?) {
    for (editor in EditorFactory.getInstance().allEditors) {
      if (editor.project != project) continue
      val f = FileDocumentManager.getInstance().getFile(editor.document) ?: continue
      if ((file == null || f == file) && f.fileType == SqlFileType.INSTANCE) paint(editor, f)
    }
    val editors = FileEditorManager.getInstance(project)
    for (f in editors.openFiles) if ((file == null || f == file) && f.fileType == SqlFileType.INSTANCE) editors.updateFilePresentation(f)
    DbResultsToolWindow.repaintTabs(project)
  }

  /** Sets the tint as the editor's background; an editor this never tinted is left alone. */
  fun paint(editor: Editor, file: VirtualFile) {
    val ex = editor as? EditorEx ?: return
    val tint = DbColors.tintFor(project, file)
    if (tint == null && ex.getUserData(TINTED) != true) return
    ex.setBackgroundColor(tint)
    ex.putUserData(TINTED, tint != null)
  }

  override fun dispose() {}

  companion object {
    private val TINTED = Key.create<Boolean>("phantom.db.tinted")

    fun getInstance(project: Project): DbColorService = project.service()
  }
}
