// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.ui.JediTermWidget
import dev.phantom.ai.remote.AnsiColor
import dev.phantom.ai.remote.Run
import java.util.concurrent.TimeUnit

/** A terminal screen at one moment: styled lines, its size and the 0-based cursor cell. */
class ScreenSnapshot(val lines: List<List<Run>>, val cols: Int, val rows: Int, val cursorX: Int, val cursorY: Int)

/** What the remote control needs of a terminal: read its screen, type into it, hear that it changed. Fakeable. */
interface TerminalIo {
  fun snapshot(): ScreenSnapshot
  fun send(text: String)

  /** Calls [listener] on every change of the screen until [parent] is disposed. */
  fun onChange(parent: Disposable, listener: () -> Unit)
}

/**
 * Sends [prompt] to the agent of this terminal as one bracketed paste, so its newlines stay in the message, and Enter a
 * moment later: Claude and Codex take an Enter that arrives with the paste as part of it.
 */
fun TerminalIo.submit(
  prompt: String,
  later: (Runnable) -> Unit = { AppExecutorUtil.getAppScheduledExecutorService().schedule(it, 300, TimeUnit.MILLISECONDS) },
) {
  send("\u001b[200~$prompt\u001b[201~")
  later(Runnable { send("\r") })
}

/** [TerminalIo] of a JediTerm widget: the screen is read under the text buffer's lock. */
class JediTermIo(private val widget: JediTermWidget) : TerminalIo {
  override fun snapshot(): ScreenSnapshot {
    val buffer = widget.terminalTextBuffer
    buffer.lock()
    try {
      val lines = (0 until buffer.height).map { y ->
        buffer.getLine(y).entries.mapNotNull { e ->
          val text = e.text.toString().replace('\u0000', ' ')
          if (text.isEmpty()) null else run(text, e.style)
        }
      }
      val terminal = widget.terminal
      return ScreenSnapshot(lines, buffer.width, buffer.height, terminal.cursorX - 1, terminal.cursorY - 1)
    } finally {
      buffer.unlock()
    }
  }

  override fun send(text: String) {
    widget.terminalStarter?.sendString(text, true)
  }

  override fun onChange(parent: Disposable, listener: () -> Unit) {
    val buffer = widget.terminalTextBuffer
    val l = TerminalModelListener { listener() }
    buffer.addModelListener(l)
    Disposer.register(parent) { buffer.removeModelListener(l) }
  }

  private fun run(text: String, style: TextStyle) = Run(
    text, color(style.foreground), color(style.background),
    bold = style.hasOption(TextStyle.Option.BOLD), italic = style.hasOption(TextStyle.Option.ITALIC),
    underline = style.hasOption(TextStyle.Option.UNDERLINED), inverse = style.hasOption(TextStyle.Option.INVERSE),
  )

  private fun color(c: TerminalColor?): AnsiColor? = when {
    c == null -> null
    c.isIndexed -> AnsiColor.Indexed(c.colorIndex)
    else -> c.toColor().let { AnsiColor.Rgb(it.red, it.green, it.blue) }
  }
}
