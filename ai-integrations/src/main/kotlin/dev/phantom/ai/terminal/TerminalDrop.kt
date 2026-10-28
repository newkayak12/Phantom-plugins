// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import dev.phantom.ai.core.bestEffort
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JComponent

/**
 * What a drop of files on an agent terminal types, as Orca does (`terminal-drop-path-writer.ts`): an image as a
 * bracketed paste of its path, which Claude and Codex take as an image attachment; any other file as its shell-escaped
 * path. Paths are separated by a space, except between two image pastes.
 */
object TerminalDropText {
  private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico")
  private val SAFE = Regex("^[a-zA-Z0-9_./@:-]+$")
  private val SPECIAL = Regex("""[ "'`$;&|<>(){}\[\]*?!#\\]""")
  private const val PASTE_START = "\u001b[200~"
  private const val PASTE_END = "\u001b[201~"

  fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

  fun of(paths: List<String>): String {
    val out = StringBuilder()
    paths.forEachIndexed { i, path ->
      val image = isImage(path) && path.none { it.isISOControl() }
      if (image) {
        // Claude strips only one quote pair and Codex shlex-splits a raw spaced path: backslashes, never quotes
        out.append(PASTE_START).append(path.replace(SPECIAL) { "\\" + it.value }).append(PASTE_END)
        val next = paths.getOrNull(i + 1)
        if (next == null || !isImage(next)) out.append(' ')
      } else {
        out.append(shellEscape(path)).append(' ')
      }
    }
    return out.toString()
  }

  /** POSIX: bare when only safe characters, else single-quoted. */
  fun shellEscape(path: String): String = if (SAFE.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"
}

/**
 * Lets an agent terminal take files dropped from Finder or the IDE (Project view, editor tabs): their paths are typed
 * into the terminal ([TerminalDropText]). A macOS screenshot dragged from its floating preview lives in a temporary
 * item macOS deletes soon after, so it is copied to a temp file of ours first.
 */
object TerminalDrop {
  private val LOG = logger<TerminalDrop>()

  fun install(component: JComponent, parent: Disposable, type: (String) -> Unit) {
    DnDSupport.createBuilder(component)
      .disableAsSource()
      .enableAsNativeTarget()
      .setTargetChecker { event ->
        event.isDropPossible = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject).isNotEmpty()
        true
      }
      .setDropHandler(DnDDropHandler { event ->
        val files = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject)
        if (files.isEmpty()) return@DnDDropHandler
        val paths = files.map { keep(it).path }
        type(TerminalDropText.of(paths))
        component.requestFocusInWindow()
      })
      .setDisposableParent(parent)
      .install()
  }

  /** A file under macOS's `TemporaryItems/NSIRD_*` (a screenshot preview) is copied out; any other file is used as is. */
  private fun keep(file: File): File {
    if (!file.path.contains("/TemporaryItems/NSIRD_")) return file
    return bestEffort {
      val dir = Files.createTempDirectory("phantom-drop-")
      Files.copy(file.toPath(), dir.resolve(file.name), StandardCopyOption.REPLACE_EXISTING).toFile()
    }.onFailure { LOG.debug("copying the dropped screenshot failed", it) }.getOrDefault(file)
  }
}
