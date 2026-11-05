// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

fun interface AiMemoListener {
  fun changed()
}

/** A memo on a line range; [marker] keeps the range as the document is edited. Lines are 0-based. */
class AiMemo(val id: Long, val file: VirtualFile, val marker: RangeMarker, var text: String, var checked: Boolean = true) {
  val startLine: Int get() = marker.document.getLineNumber(marker.startOffset)
  val endLine: Int get() = marker.document.getLineNumber(marker.endOffset)
}

/** Memory-only memos of the project, in the order the prompt lists them. */
@Service(Service.Level.PROJECT)
class AiMemoStore(private val project: Project, private val root: () -> String?) : Disposable {
  constructor(project: Project) : this(project, { project.basePath })

  private val memos = CopyOnWriteArrayList<AiMemo>()
  private val ids = AtomicLong()
  private val listeners = ConcurrentHashMap<Long, Disposable>()

  fun worktree(): String? = root()

  fun add(document: Document, startLine: Int, endLine: Int, text: String): AiMemo {
    val file = requireNotNull(FileDocumentManager.getInstance().getFile(document)) { "document has no file" }
    val marker = document.createRangeMarker(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine))
    marker.isGreedyToRight = true
    val memo = AiMemo(ids.incrementAndGet(), file, marker, text)
    val wasEmpty = marker.startOffset == marker.endOffset
    val listenerScope = Disposer.newDisposable(this, "AiMemo ${memo.id}")
    listeners[memo.id] = listenerScope
    memos.add(memo)
    document.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
          if (!marker.isValid || (!wasEmpty && marker.startOffset == marker.endOffset)) {
            release(memo)
            if (memos.remove(memo)) publish()
          }
        }
      },
      listenerScope,
    )
    publish()
    return memo
  }

  fun update(id: Long, text: String) {
    memos.firstOrNull { it.id == id }?.text = text
    publish()
  }

  fun setChecked(id: Long, checked: Boolean) {
    memos.firstOrNull { it.id == id }?.checked = checked
    publish()
  }

  fun remove(ids: Collection<Long>) {
    val gone = memos.filter { it.id in ids }
    gone.forEach { release(it) }
    if (memos.removeAll(gone.toSet())) publish()
  }

  fun all(): List<AiMemo> = memos.filter { it.marker.isValid }.sortedWith(compareBy({ it.file.path }, { it.startLine }))

  fun inFile(file: VirtualFile): List<AiMemo> = all().filter { it.file == file }

  fun toItem(memo: AiMemo): MemoItem? {
    val base = worktree()?.trimEnd('/') ?: return null
    val path = memo.file.path
    if (!path.startsWith("$base/")) return null
    val language = memo.file.fileType.name.lowercase().let { if (it == "plain_text") "" else it }
    val code = memo.marker.document.getText(
      com.intellij.openapi.util.TextRange(
        memo.marker.document.getLineStartOffset(memo.startLine),
        memo.marker.document.getLineEndOffset(memo.endLine),
      ),
    )
    return MemoItem(path.removePrefix("$base/"), memo.startLine + 1, memo.endLine + 1, language, code, memo.text)
  }

  private fun release(memo: AiMemo) {
    listeners.remove(memo.id)?.let { Disposer.dispose(it) }
    memo.marker.dispose()
  }

  private fun publish() = project.messageBus.syncPublisher(TOPIC).changed()

  override fun dispose() {
    memos.forEach { it.marker.dispose() }
    memos.clear()
  }

  companion object {
    val TOPIC: Topic<AiMemoListener> = Topic.create("AI memo changes", AiMemoListener::class.java)

    fun getInstance(project: Project): AiMemoStore = project.getService(AiMemoStore::class.java)
  }
}
