// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import dev.phantom.ai.core.PhantomAiBundle
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JComponent

/**
 * The memo blocks of one editor, as in the IDE's pull request review: the range outlined together with a header above it and
 * the memo (or the input field) below it; yellow when saved, blue while editing. Also the gutter `+` that opens an input for a line
 * or a dragged range. EDT only.
 */
class MemoEditorBlocks(private val editor: Editor, private val store: AiMemoStore) : Disposable {
  private class Block(val header: Inlay<*>?, val body: Inlay<*>?, val outline: RangeHighlighter) {
    fun dispose() {
      header?.let { Disposer.dispose(it) }
      body?.let { Disposer.dispose(it) }
      outline.dispose()
    }
  }

  private class Input(val memoId: Long?, val marker: RangeMarker, val block: Block, val body: MemoInputBody, val scope: Disposable)

  private val project = requireNotNull(editor.project) { "memo blocks need a project editor" }
  private val saved = mutableListOf<Block>()
  private var input: Input? = null
  private var disposed = false
  private val gutter: GutterPlus?

  init {
    editor.putUserData(KEY, this)
    project.messageBus.connect(this).subscribe(AiMemoStore.TOPIC, AiMemoListener { refresh() })
    // the header shows the line numbers: redraw when lines come or go above or inside a block
    editor.document.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
          if ('\n' in event.oldFragment || '\n' in event.newFragment) later()
        }
      },
      this,
    )
    gutter = (editor as? EditorEx)?.let { GutterPlus(it, this) { start, end -> openInput(start, end) } }
    rebuild()
  }

  /** Opens the input block for the 0-based lines [startLine]..[endLine]; an open input is closed first. */
  fun openInput(startLine: Int, endLine: Int) = open(startLine, endLine, null)

  @TestOnly
  fun blockCount(): Int = saved.size

  @TestOnly
  fun inputOpen(): Boolean = input != null

  /** The gutter x where the `+` is drawn and clicked. */
  @TestOnly
  fun plusX(): Int = requireNotNull(gutter).slotX()

  /** What the edit button of [memo]'s block does. */
  @TestOnly
  fun editInput(memo: AiMemo) = open(memo.startLine, memo.endLine, memo)

  @TestOnly
  fun inputFocus(): JComponent = requireNotNull(requireNotNull(input).body.fieldEditor()).contentComponent

  @TestOnly
  fun inputText(): String = requireNotNull(input).body.field.text

  /** What ↩ (or ⌘↩ when [send]) does in the input field. */
  @TestOnly
  fun submitInput(text: String, send: Boolean = false) = submit(text, send)

  private fun open(startLine: Int, endLine: Int, memo: AiMemo?) {
    val wasEditing = input?.memoId != null
    closeInput()
    val doc = editor.document
    val last = maxOf(doc.lineCount - 1, 0)
    val start = minOf(startLine, endLine).coerceIn(0, last)
    val end = maxOf(startLine, endLine).coerceIn(0, last)
    val marker = doc.createRangeMarker(doc.getLineStartOffset(start), doc.getLineEndOffset(end)).apply { isGreedyToRight = true }
    val lines = lines(start, end)
    val title = if (memo == null) PhantomAiBundle.message("memo.input.new", lines) else PhantomAiBundle.message("memo.input.edit", lines)
    val scope = Disposer.newDisposable(this, "AI memo input")
    val body = MemoInputBody(project, scope, memo?.text.orEmpty(), { text, send -> submit(text, send) }, { cancel() })
    val block = block(start, end, MemoBlockComponents.inputHeader(title) { cancel() }, body, MemoBlockStyle.editing)
    input = Input(memo?.id, marker, block, body, scope)
    // the memo being edited gives way to the input; a memo whose edit was just dropped comes back
    if (memo != null || wasEditing) rebuild()
    IdeFocusManager.getInstance(project).requestFocus(body.field, true)
  }

  private fun submit(text: String, send: Boolean) {
    val current = input ?: return
    val memo = text.trim()
    val marker = current.marker
    if (memo.isEmpty() || !marker.isValid) return cancel()
    val doc = editor.document
    val start = doc.getLineNumber(marker.startOffset)
    val end = doc.getLineNumber(marker.endOffset)
    closeInput()
    if (current.memoId != null) store.update(current.memoId, memo) else store.add(doc, start, end, memo)
    IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    // ⌘↩: the send action (registered by the memo send popup) if it is there
    if (send) {
      ActionManager.getInstance().getAction(SEND_ACTION_ID)?.let {
        val context = DataManager.getInstance().getDataContext(editor.contentComponent)
        val event = AnActionEvent.createEvent(it, context, null, ActionPlaces.EDITOR_INLAY, ActionUiKind.NONE, null)
        ActionUtil.updateAction(it, event)
        if (event.presentation.isEnabled) ActionUtil.performAction(it, event)
      }
    }
  }

  private fun cancel() {
    val editing = input?.memoId
    closeInput()
    if (editing != null) rebuild()
    IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
  }

  private fun closeInput() {
    val current = input ?: return
    input = null
    current.block.dispose()
    current.marker.dispose()
    Disposer.dispose(current.scope)
  }

  private fun refresh() {
    // the store also publishes from inside a document change (a memo whose code was deleted): inlays wait for it to end
    if ((editor.document as? DocumentEx)?.isInEventsHandling == true) later() else rebuild()
  }

  private fun later() = ApplicationManager.getApplication().invokeLater({ rebuild() }, { disposed || project.isDisposed })

  private fun rebuild() {
    if (disposed) return
    saved.forEach { it.dispose() }
    saved.clear()
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
    val all = store.all()
    all.forEachIndexed { index, memo ->
      if (memo.file != file || memo.id == input?.memoId || !memo.marker.isValid) return@forEachIndexed
      val title = PhantomAiBundle.message("memo.block.title", index + 1, all.size, lines(memo.startLine, memo.endLine))
      val header = MemoBlockComponents.savedHeader(title, { open(memo.startLine, memo.endLine, memo) }, { store.remove(listOf(memo.id)) })
      saved += block(memo.startLine, memo.endLine, header, MemoBlockComponents.savedBody(memo.text), MemoBlockStyle.saved)
    }
  }

  private fun block(start: Int, end: Int, header: JComponent, body: JComponent, color: Color): Block {
    val doc = editor.document
    val headerInlay = editor.addComponentInlay(
      doc.getLineStartOffset(start),
      InlayProperties().showAbove(true).relatesToPrecedingText(false).priority(PRIORITY),
      CodeReviewComponentInlayRenderer(header),
    )
    val bodyInlay = editor.addComponentInlay(
      doc.getLineEndOffset(end),
      InlayProperties().showAbove(false).relatesToPrecedingText(true).priority(PRIORITY),
      CodeReviewComponentInlayRenderer(body),
    )
    val outline = editor.markupModel.addRangeHighlighter(
      doc.getLineStartOffset(start), doc.getLineEndOffset(end), HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE,
    )
    outline.customRenderer = RangeOutline(headerInlay, color)
    return Block(headerInlay, bodyInlay, outline)
  }

  override fun dispose() {
    disposed = true
    if (editor.getUserData(KEY) === this) editor.putUserData(KEY, null)
    closeInput()
    saved.forEach { it.dispose() }
    saved.clear()
  }

  /** The sides of the outline along the code lines, lined up with the header and the body above and below them. */
  private class RangeOutline(private val header: Inlay<*>?, private val color: Color) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
      if (!highlighter.isValid) return
      val doc = editor.document
      val top = editor.visualLineToY(editor.offsetToVisualLine(doc.getLineStartOffset(doc.getLineNumber(highlighter.startOffset)), false))
      val bottom = editor.visualLineToY(editor.offsetToVisualLine(doc.getLineEndOffset(doc.getLineNumber(highlighter.endOffset)), true)) +
        editor.lineHeight
      val area = header?.bounds ?: editor.scrollingModel.visibleArea
      val inset = MemoBlockStyle.inset()
      val left = area.x + inset
      val right = area.x + area.width - 1 - inset
      val g2 = MemoBlockStyle.antialias(g)
      try {
        g2.color = MemoBlockStyle.fill(color)
        g2.fillRect(left, top, right - left, bottom - top)
        g2.color = color
        g2.drawLine(left, top, left, bottom)
        g2.drawLine(right, top, right, bottom)
      } finally {
        g2.dispose()
      }
    }
  }

  /**
   * The gutter `+`: shown on the hovered line in a slot of its own (the left free painters area, as the review gutter controls do),
   * so run, override and breakpoint icons keep their clicks. A click on it opens an input for that line, a drag for the dragged lines.
   */
  private class GutterPlus(private val editor: EditorEx, parent: Disposable, private val open: (Int, Int) -> Unit) : LineMarkerRendererEx {
    private val icon = AllIcons.General.InlineAdd
    private var hovered: Int? = null
    private var dragFrom: Int? = null
    private var dragTo: Int? = null
    private var swallowClick = false

    init {
      editor.gutterComponentEx.reserveLeftFreePaintersAreaWidth(parent, icon.iconWidth)
      val highlighter = editor.markupModel.addRangeHighlighter(
        0, editor.document.textLength, HighlighterLayer.LAST, null, HighlighterTargetArea.LINES_IN_RANGE,
      )
      highlighter.isGreedyToLeft = true
      highlighter.isGreedyToRight = true
      highlighter.lineMarkerRenderer = this
      Disposer.register(parent) { highlighter.dispose() }
      editor.addEditorMouseMotionListener(
        object : EditorMouseMotionListener {
          override fun mouseMoved(e: EditorMouseEvent) = hover(if (inGutter(e)) line(e) else null)

          override fun mouseDragged(e: EditorMouseEvent) {
            if (dragFrom == null) return
            dragTo = line(e)
            hover(dragTo)
          }
        },
        parent,
      )
      editor.addEditorMouseListener(
        object : EditorMouseListener {
          override fun mousePressed(e: EditorMouseEvent) {
            swallowClick = false
            if (!javax.swing.SwingUtilities.isLeftMouseButton(e.mouseEvent)) return
            if (!inGutter(e) || e.mouseEvent.x !in slotX() until slotX() + icon.iconWidth) return
            val line = line(e)
            dragFrom = line
            dragTo = line
            e.consume()
          }

          override fun mouseReleased(e: EditorMouseEvent) {
            val from = dragFrom ?: return
            val to = dragTo ?: from
            dragFrom = null
            dragTo = null
            swallowClick = true
            e.consume()
            editor.gutterComponentEx.repaint()
            open(minOf(from, to), maxOf(from, to))
          }

          // the click that follows our press and release is ours too (not a breakpoint toggle)
          override fun mouseClicked(e: EditorMouseEvent) {
            if (swallowClick) e.consume()
            swallowClick = false
          }

          override fun mouseExited(e: EditorMouseEvent) {
            if (dragFrom == null) hover(null)
          }
        },
        parent,
      )
    }

    private fun inGutter(e: EditorMouseEvent) = e.area != null && e.area != EditorMouseEventArea.EDITING_AREA

    private fun line(e: EditorMouseEvent) =
      editor.xyToLogicalPosition(e.mouseEvent.point).line.coerceIn(0, maxOf(editor.document.lineCount - 1, 0))

    fun slotX() = editor.gutterComponentEx.lineMarkerAreaOffset

    private fun hover(line: Int?) {
      if (line == hovered) return
      hovered = line
      editor.gutterComponentEx.repaint()
    }

    private fun y(line: Int) = editor.visualLineToY(editor.logicalToVisualPosition(LogicalPosition(line, 0)).line)

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
      val from = dragFrom
      val to = dragTo
      if (from != null && to != null) {
        g.color = MemoBlockStyle.editing
        val top = y(minOf(from, to))
        g.fillRect(slotX() + icon.iconWidth / 2 - 1, top, 2, y(maxOf(from, to)) + editor.lineHeight - top)
      }
      val line = hovered ?: return
      icon.paintIcon(editor.component, g, slotX(), y(line) + (editor.lineHeight - icon.iconHeight) / 2)
    }

    override fun getPosition() = LineMarkerRendererEx.Position.LEFT
  }

  companion object {
    /** Registered by the memo send popup (Task 6); ⌘↩ runs it when it is there. */
    const val SEND_ACTION_ID = "dev.phantom.ai.SendAiMemos"
    private const val PRIORITY = 100
    private val KEY = Key.create<MemoEditorBlocks>("dev.phantom.ai.memo.blocks")

    fun of(editor: Editor): MemoEditorBlocks? = editor.getUserData(KEY)

    fun inWorktree(store: AiMemoStore, file: VirtualFile?): Boolean {
      val root = store.worktree()?.trimEnd('/') ?: return false
      return file != null && file.path.startsWith("$root/")
    }

    /** The blocks of [editor], made on first use; null for an editor without a project or outside the worktree. */
    fun attach(editor: Editor): MemoEditorBlocks? {
      of(editor)?.let { return it }
      val project = editor.project ?: return null
      if (project.isDisposed) return null
      val store = AiMemoStore.getInstance(project)
      if (!inWorktree(store, FileDocumentManager.getInstance().getFile(editor.document))) return null
      return MemoEditorBlocks(editor, store).also { Disposer.register(store, it) }
    }

    fun detach(editor: Editor) {
      of(editor)?.let { Disposer.dispose(it) }
    }

    private fun lines(start: Int, end: Int): String =
      if (start == end) PhantomAiBundle.message("memo.lines.one", start + 1) else PhantomAiBundle.message("memo.lines", start + 1, end + 1)
  }
}

/** Gives every file editor of a worktree file its memo blocks and the gutter `+` (not diffs, consoles or previews). */
class MemoEditorFactoryListener : EditorFactoryListener {
  override fun editorCreated(event: EditorFactoryEvent) {
    val editor = event.editor
    // UNTYPED: the text editors of the test FileEditorManager
    if (!editor.isViewer && (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.UNTYPED)) MemoEditorBlocks.attach(editor)
  }

  override fun editorReleased(event: EditorFactoryEvent) = MemoEditorBlocks.detach(event.editor)
}
