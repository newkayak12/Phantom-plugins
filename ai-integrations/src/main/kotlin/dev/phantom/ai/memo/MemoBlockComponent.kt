// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.ai.core.PhantomAiBundle
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** The look of a memo block: a rounded outline (blue while editing, yellow when saved) around the code and the memo. */
internal object MemoBlockStyle {
  val editing: Color = JBColor(0x3574F0, 0x548AF7)
  val saved: Color = JBColor(0xD4A72C, 0xC9A227)

  fun arc(): Int = JBUIScale.scale(10)

  /** Space between the viewport edges and the outline. */
  fun inset(): Int = JBUIScale.scale(4)

  fun fill(color: Color): Color = ColorUtil.withAlpha(color, 0.07)

  /** The outline of a segment of the block [w] by [h]: the top and the bottom are rounded only where the block starts or ends. */
  fun shape(w: Int, h: Int, top: Boolean, bottom: Boolean): RoundRectangle2D {
    val arc = arc().toDouble()
    val above = if (top) 0.0 else arc * 2
    val below = if (bottom) 0.0 else arc * 2
    val inset = inset().toDouble()
    return RoundRectangle2D.Double(inset, -above, w - 1 - 2 * inset, h - 1 + above + below, arc, arc)
  }

  fun antialias(g: Graphics): Graphics2D = (g.create() as Graphics2D).also {
    it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  }
}

/** One segment of a memo block (the header above the code or the body below it) that paints its part of the outline. */
internal open class OutlinePanel(private val color: Color, private val top: Boolean, private val bottom: Boolean) : JPanel(BorderLayout()) {
  init {
    isOpaque = false
    val inset = MemoBlockStyle.inset()
    border = JBUI.Borders.empty(if (top) 4 else 2, 8 + inset, if (bottom) 6 else 2, 8 + inset)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = MemoBlockStyle.antialias(g)
    try {
      g2.clipRect(0, 0, width, height)
      val shape = MemoBlockStyle.shape(width, height, top, bottom)
      g2.color = MemoBlockStyle.fill(color)
      g2.fill(shape)
      g2.color = color
      g2.draw(shape)
    } finally {
      g2.dispose()
    }
  }
}

internal object MemoBlockComponents {
  /** Header of a saved memo: `AI 메모 n/N · a–b줄`, then edit and delete. */
  fun savedHeader(title: String, onEdit: () -> Unit, onDelete: () -> Unit): JComponent =
    OutlinePanel(MemoBlockStyle.saved, top = true, bottom = false).apply {
      add(JBLabel(title, AllIcons.Toolwindows.ToolWindowMessages, JBLabel.LEADING).apply { foreground = MemoBlockStyle.saved }, BorderLayout.CENTER)
      add(
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
          isOpaque = false
          add(InplaceButton(PhantomAiBundle.message("memo.block.edit"), AllIcons.Actions.Edit) { onEdit() })
          add(InplaceButton(PhantomAiBundle.message("memo.block.delete"), AllIcons.Actions.GC) { onDelete() })
        },
        BorderLayout.EAST,
      )
    }

  /** Body of a saved memo: the memo text under the code. */
  fun savedBody(text: String): JComponent = OutlinePanel(MemoBlockStyle.saved, top = false, bottom = true).apply {
    add(readOnlyText(text), BorderLayout.CENTER)
  }

  fun inputHeader(title: String, onCancel: () -> Unit): JComponent =
    OutlinePanel(MemoBlockStyle.editing, top = true, bottom = false).apply {
      add(JBLabel(title).apply { foreground = MemoBlockStyle.editing }, BorderLayout.CENTER)
      add(InplaceButton(PhantomAiBundle.message("memo.input.cancel"), AllIcons.Actions.Close) { onCancel() }, BorderLayout.EAST)
    }

  private fun readOnlyText(text: String) = JBTextArea(text).apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    border = JBUI.Borders.empty(2, 0)
    font = UIUtil.getLabelFont()
  }
}

/**
 * Body of the input block: the memo field and the key hint. ↩ saves, ⇧↩ breaks the line, Esc cancels, ⌘↩ (Ctrl+↩) saves and sends.
 * The field is an editor of its own (EditorTextField), so the IDE's editor actions (Backspace, Delete, arrows, Select All, Paste,
 * Undo...) work on it and never on the code editor it sits in. The four keys are registered as actions on the field, which the
 * IDE tries before the keymap.
 */
internal class MemoInputBody(project: Project, scope: Disposable, initial: String, onSubmit: (String, Boolean) -> Unit, onCancel: () -> Unit) :
  OutlinePanel(MemoBlockStyle.editing, top = false, bottom = true) {
  val field = EditorTextField(initial, project, FileTypes.PLAIN_TEXT).apply {
    setOneLineMode(false)
    font = UIUtil.getLabelFont()
    addSettingsProvider {
      it.settings.isUseSoftWraps = true
      it.setBorder(JBUI.Borders.empty(4, 6))
    }
    border = JBUI.Borders.customLine(MemoBlockStyle.editing, 1)
    setDisposedWith(scope) // its editor lives as long as the input, on screen or not
  }

  init {
    add(field, BorderLayout.CENTER)
    val send = if (SystemInfo.isMac) "⌘" else "Ctrl+"
    add(
      JBLabel(PhantomAiBundle.message("memo.input.hint", send)).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(4)
      },
      BorderLayout.SOUTH,
    )
    val menu = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
    key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) { onSubmit(field.text, false) }
    key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu)) { onSubmit(field.text, true) }
    key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)) {
      val editor = field.editor ?: return@key
      WriteCommandAction.runWriteCommandAction(project) { EditorModificationUtil.insertStringAtCaret(editor, "\n") }
    }
    key(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) { onCancel() }
  }

  /** The field's own editor, made now if the field is not on screen yet. */
  fun fieldEditor(): Editor? = field.getEditor(true)

  private fun key(stroke: KeyStroke, run: () -> Unit) {
    object : DumbAwareAction() {
      override fun actionPerformed(e: AnActionEvent) = run()
    }.registerCustomShortcutSet(CustomShortcutSet(stroke), field)
  }
}
