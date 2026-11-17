// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.mcp

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.util.ui.UIUtil
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import dev.phantom.ai.core.bestEffort
import dev.phantom.workspaces.core.ProjectTabsService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The real [EditorBridge]. A file opens in the open project holding it (the deepest one), else in the calling agent's
 * worktree, opened as a project (a Workspaces tab) when it is not open yet, as the sidebar's "Open in Phantom" does.
 */
/** The end of a console's text for an agent: few lines, filtered, with what it is. */
internal object RunOutput {
  fun answer(title: String, state: String, text: String, lines: Int, grep: String?): String {
    val all = text.lines().dropLastWhile { it.isEmpty() }
    val regex = grep?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrElse { _ -> Regex(Regex.escape(grep), RegexOption.IGNORE_CASE) } }
    val kept = if (regex == null) all else all.filter { regex.containsMatchIn(it) }
    val shown = kept.takeLast(lines).map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
    val header = "[$title${if (state.isEmpty()) "" else ": $state"}] " + when {
      regex != null -> "${kept.size} of ${all.size} lines match" + (if (kept.size > shown.size) ", the last ${shown.size}" else "")
      kept.size > shown.size -> "the last ${shown.size} of ${all.size} lines"
      else -> "${all.size} lines"
    }
    return (listOf(header) + shown).joinToString("\n")
  }

  private const val MAX_LINE = 1000
}

class IdeEditorBridge(
  /** Opens a worktree that is not open yet as a project (a new Workspaces tab). Fakeable. */
  private val openWorktree: (Path) -> Project? = { ProjectUtil.openOrImport(it, null, true) },
) : EditorBridge {
  override fun open(worktree: String?, path: String, line: Int?, column: Int?, endLine: Int?): String {
    val file = resolve(worktree, path)
    if (!Files.isRegularFile(file)) throw McpToolError("No such file: $file")
    // off the EDT: a refresh is a disk walk
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: throw McpToolError("Cannot open $file")
    return onEdt {
      val project = projectFor(file, worktree) ?: throw McpToolError("No Phantom window to open $file in")
      bestEffort { ProjectTabsService.getInstance().switchTo(project) }
      val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
      val lines = document?.lineCount ?: 0
      val line0 = line?.let { (it - 1).coerceIn(0, (lines - 1).coerceAtLeast(0)) } ?: -1
      val column0 = column?.let { (it - 1).coerceAtLeast(0) } ?: 0
      val descriptor = if (line0 >= 0) OpenFileDescriptor(project, vf, line0, column0) else OpenFileDescriptor(project, vf)
      val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
      if (editor != null && line0 >= 0) {
        val last = endLine?.let { (it - 1).coerceIn(line0, (lines - 1).coerceAtLeast(line0)) }
        if (last != null && last > line0) {
          editor.selectionModel.setSelection(document!!.getLineStartOffset(line0), document.getLineEndOffset(last))
        }
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
      }
      ProjectUtil.focusProjectWindow(project, true)
      file.toString()
    }
  }

  override fun context(worktree: String?): JsonObject = onEdt {
    val project = focusedProject() ?: worktree?.let(::openProjectAt) ?: throw McpToolError("No Phantom window is open")
    val manager = FileEditorManager.getInstance(project)
    val editor = manager.selectedTextEditor
    JsonObject().apply {
      addProperty("project", project.basePath)
      if (editor == null) {
        addProperty("file", null as String?)
      } else {
        val document = editor.document
        val caret = editor.caretModel.logicalPosition
        addProperty("file", manager.selectedFiles.firstOrNull()?.path)
        addProperty("line", caret.line + 1)
        addProperty("column", caret.column + 1)
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
          val text = selection.selectedText.orEmpty()
          addProperty("selectionStartLine", document.getLineNumber(selection.selectionStart) + 1)
          addProperty("selectionEndLine", document.getLineNumber(selection.selectionEnd) + 1)
          addProperty("selectedText", text.take(MAX_SELECTION))
          if (text.length > MAX_SELECTION) addProperty("selectionTruncated", true)
        }
      }
      add("openFiles", JsonArray().apply { manager.openFiles.take(MAX_OPEN_FILES).forEach { add(it.path) } })
    }
  }

  override fun runOutput(worktree: String?, name: String?, lines: Int, grep: String?): String = onEdt {
    val project = worktree?.let { w -> ProjectManager.getInstance().openProjects
      .filter { p -> p.basePath?.let { Path.of(w).normalize().startsWith(Path.of(it)) } == true }
      .maxByOrNull { it.basePath!!.length } } ?: focusedProject() ?: throw McpToolError("No Phantom window is open")
    val manager = RunContentManager.getInstance(project)
    val all = manager.allDescriptors
    if (all.isEmpty()) throw McpToolError("No Run, Debug or test console is open in ${project.name}")
    val descriptor = if (name != null) {
      all.lastOrNull { it.displayName.contains(name, ignoreCase = true) }
        ?: throw McpToolError("No console named like \"$name\"; the consoles: " + all.joinToString { it.displayName })
    } else {
      manager.selectedContent ?: all.last()
    }
    val console = descriptor.executionConsole ?: throw McpToolError("${descriptor.displayName} has no console")
    (console as? ConsoleViewImpl)?.flushDeferredText()
    // a test console holds the output in an inner console view; take the first console editor inside it
    val editor = (console as? ConsoleViewImpl)?.editor
      ?: UIUtil.findComponentsOfType(console.component, EditorComponentImpl::class.java).firstOrNull()?.editor
      ?: throw McpToolError("${descriptor.displayName} shows no text output")
    val process = descriptor.processHandler
    val state = when {
      process == null -> ""
      !process.isProcessTerminated -> "running"
      else -> "finished" + (process.exitCode?.let { ", exit code $it" } ?: "")
    }
    RunOutput.answer(descriptor.displayName, state, editor.document.text, lines, grep)
  }

  private fun resolve(worktree: String?, path: String): Path {
    val given = Path.of(path.removePrefix("file://"))
    val absolute = if (given.isAbsolute) given else (worktree?.let(Path::of) ?: throw McpToolError("Give an absolute path")).resolve(given)
    return absolute.normalize()
  }

  /** The deepest open project holding [file]; else the worktree's project, opened if needed; else the focused window's. */
  private fun projectFor(file: Path, worktree: String?): Project? {
    val holding = ProjectManager.getInstance().openProjects
      .filter { p -> p.basePath?.let { file.startsWith(Path.of(it)) } == true }
      .maxByOrNull { it.basePath!!.length }
    if (holding != null) return holding
    if (worktree != null && file.startsWith(Path.of(worktree))) {
      return openProjectAt(worktree) ?: openWorktree(Path.of(worktree))
    }
    return focusedProject()
  }

  private fun openProjectAt(path: String): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { it.basePath?.let(Path::of) == Path.of(path) }

  private fun focusedProject(): Project? =
    IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeIf { !it.isDisposed }
      ?: ProjectManager.getInstance().openProjects.firstOrNull()

  /** Runs [block] on the EDT; a modal dialog holding it past [TIMEOUT_S] is an error, not a hung hook server thread. */
  private fun <T> onEdt(block: () -> T): T {
    val future = CompletableFuture<T>()
    ApplicationManager.getApplication().invokeLater({
      try { future.complete(block()) } catch (e: Throwable) { future.completeExceptionally(e) }
    }, ModalityState.nonModal())
    return try {
      future.get(TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      throw McpToolError("Phantom is busy (a dialog is open); try again when it is closed")
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
  }

  companion object {
    private const val TIMEOUT_S = 15L
    private const val MAX_SELECTION = 20_000
    private const val MAX_OPEN_FILES = 30
  }
}
