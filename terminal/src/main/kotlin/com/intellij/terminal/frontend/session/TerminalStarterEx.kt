package com.intellij.terminal.frontend.session

import com.intellij.openapi.diagnostic.thisLogger
import com.jediterm.core.input.KeyEvent
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.terminal.TerminalDataStream
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.JediTerminal
import org.jetbrains.plugins.terminal.fus.BatchLatencyReporter
import java.io.IOException
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.TimeMark
import kotlin.time.Duration

internal class TerminalStarterEx(
  terminal: JediTerminal,
  private val ttyConnector: TtyConnector,
  dataStream: TerminalDataStream,
  typeAheadManager: TerminalTypeAheadManager,
  executorServiceManager: TerminalExecutorServiceManager,
) : TerminalStarter(terminal, ttyConnector, dataStream, typeAheadManager, executorServiceManager) {
  @Volatile
  var isStopped: Boolean = false
    private set

  private val singleThreadScheduledExecutor: ScheduledExecutorService = executorServiceManager.singleThreadScheduledExecutor

  @Volatile
  private var isLastSentByteEscape = false

  private val typingLatencyReporter = BatchLatencyReporter<Duration>(batchSize = 50) {} // Phantom: no usage statistics, the samples are dropped

  override fun requestEmulatorStop() {
    super.requestEmulatorStop()
    isStopped = true
  }

  /**
   * Use for sending bytes for that typing latency should be reported.
   * [eventTime] is the moment when this writing bytes event was initialized.
   */
  fun sendTypedBytes(bytes: ByteArray, eventTime: TimeMark) {
    val length = bytes.size
    if (length > 0) {
      isLastSentByteEscape = bytes[length - 1].toInt() == KeyEvent.VK_ESCAPE
    }
    execute {
      try {
        ttyConnector.write(bytes)

        val latency = eventTime.elapsedNow()
        typingLatencyReporter.update(latency)
      }
      catch (e: IOException) {
        thisLogger().info("Cannot write to TtyConnector ${ttyConnector.javaClass.getName()}, connected: ${ttyConnector.isConnected}", e)
      }
    }
  }

  override fun isLastSentByteEscape(): Boolean {
    return isLastSentByteEscape || super.isLastSentByteEscape()
  }

  private inline fun execute(crossinline action: () -> Unit) {
    if (!singleThreadScheduledExecutor.isShutdown) {
      singleThreadScheduledExecutor.execute { action() }
    }
  }
}
