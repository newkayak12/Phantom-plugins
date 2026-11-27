// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Everything the widget and the popup show; replaced as a whole on every sample, never mutated after. */
class CpuSnapshot(
  /** Last [HISTORY] samples, oldest first, in percent of the whole machine; -1 where there is no sample yet. */
  val ideHistory: IntArray,
  val systemHistory: IntArray,
  val ide: Int,
  val system: Int,
  /** The IDE has been at or above [HIGH_PERCENT] for [HIGH_SAMPLES] samples in a row. */
  val high: Boolean,
  val ideText: String,
  val systemText: String,
  /** Share of the IDE's CPU time in the last [BREAKDOWN_SAMPLES] detailed samples, by [Work.ordinal]; null until measured. */
  val breakdown: DoubleArray?,
  val topProcesses: List<TopProcess>,
  /** This plugin's sampling thread, in percent of the whole machine. */
  val self: Double,
) {
  companion object {
    const val HISTORY = 30
    val EMPTY = CpuSnapshot(IntArray(HISTORY) { -1 }, IntArray(HISTORY) { -1 }, 0, 0, false, "", "", null, emptyList(), 0.0)
  }
}

const val SAMPLE_MS = 2_000L
const val HIGH_PERCENT = 70
const val HIGH_SAMPLES = 5 // 10 seconds
const val BREAKDOWN_SAMPLES = 5 // 10 seconds
private const val TOP_PROCESSES = 3

/**
 * Samples CPU every [SAMPLE_MS] on its own daemon thread (named [THREAD_NAME], so its cost shows in thread CPU),
 * only while a status bar widget listens.
 *
 * Normally a sample is two numbers: the process CPU time and the system load. Per-thread CPU, stacks and `ps` run
 * only while a popup is open ([detailRequests]): taking stacks stops every thread at a safepoint, which a busy IDE
 * ([CpuSnapshot.high]) can afford least, so the popup measures from the moment it opens.
 */
@Service(Service.Level.APP)
class CpuSampler : Disposable {
  fun interface Listener {
    /** On the sampling thread. [changed]: a number or the graph differs from the previous snapshot. */
    fun sampled(snapshot: CpuSnapshot, changed: Boolean)
  }

  @Volatile
  var snapshot: CpuSnapshot = CpuSnapshot.EMPTY
    private set

  private val listeners = CopyOnWriteArrayList<Listener>()
  private val lock = Any()
  private var executor: ScheduledExecutorService? = null

  @Volatile
  private var detailRequests = 0

  private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private val threads = ManagementFactory.getThreadMXBean()
  private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
  private val compilation = ManagementFactory.getCompilationMXBean()
  private val cores = Runtime.getRuntime().availableProcessors()

  // Sampling thread only
  private var lastWall = 0L
  private var lastProcess = 0L
  private var lastSelf = 0L
  private var highRun = 0
  private var ideHistory = IntArray(CpuSnapshot.HISTORY) { -1 }
  private var systemHistory = IntArray(CpuSnapshot.HISTORY) { -1 }
  private var ideText = ""
  private var systemText = ""
  private var lastIde = -1
  private var lastSystem = -1
  private var detail: Detail? = null

  fun addListener(listener: Listener, parent: Disposable) {
    listeners.add(listener)
    synchronized(lock) {
      if (executor == null) {
        executor = Executors.newSingleThreadScheduledExecutor { r ->
          Thread(r, THREAD_NAME).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }.also {
          // A previous executor may have stopped mid-run; start from a fresh baseline so the first interval and the graph
          // do not span the time nobody listened.
          it.execute { resetSamplingState() }
          it.scheduleWithFixedDelay({ sampleSafely(false) }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        }
      }
    }
    Disposer.register(parent) {
      listeners.remove(listener)
      synchronized(lock) {
        if (listeners.isEmpty()) {
          executor?.shutdownNow()
          executor = null
        }
      }
    }
  }

  /** Turns on per-thread analysis and `ps` until [releaseDetail]; takes a baseline right away. */
  fun requestDetail() {
    detailRequests++
    synchronized(lock) { executor?.execute { sampleSafely(true) } }
  }

  fun releaseDetail() {
    detailRequests--
  }

  override fun dispose() {
    synchronized(lock) {
      executor?.shutdownNow()
      executor = null
    }
  }

  private fun sampleSafely(extra: Boolean) {
    try {
      if (extra) sampleExtra() else sample()
    }
    catch (e: Throwable) {
      LOG.warn(e)
    }
  }

  /** Right after a popup opens: a thread baseline and `ps`, so the popup fills in without waiting for the next sample. */
  private fun sampleExtra() {
    if (detailRequests <= 0) return
    val d = sampleDetail(System.nanoTime())
    val s = snapshot
    publish(CpuSnapshot(s.ideHistory, s.systemHistory, s.ide, s.system, s.high, s.ideText, s.systemText,
                        d.breakdown(), d.topProcesses, s.self), false)
  }

  private fun sample() {
    val wall = System.nanoTime()
    val process = os.processCpuTime
    val self = threads.currentThreadCpuTime
    val system = os.cpuLoad
    if (lastWall == 0L) {
      lastWall = wall; lastProcess = process; lastSelf = self
      return
    }
    val wallNs = wall - lastWall
    val processNs = process - lastProcess
    val selfNs = self - lastSelf
    lastWall = wall; lastProcess = process; lastSelf = self

    val ide = percent(processNs.toDouble() / (wallNs.toDouble() * cores))
    val sys = if (system.isNaN() || system < 0) ide else maxOf(ide, percent(system))
    highRun = if (ide >= HIGH_PERCENT) highRun + 1 else 0
    val high = highRun >= HIGH_SAMPLES

    val previousIde = ideHistory
    val previousSystem = systemHistory
    ideHistory = shifted(previousIde, ide)
    systemHistory = shifted(previousSystem, sys)
    val changed = ide != lastIde || sys != lastSystem || high != snapshot.high ||
                  !ideHistory.contentEquals(previousIde) || !systemHistory.contentEquals(previousSystem)
    if (ide != lastIde) ideText = PhantomCpuBundle.message("widget.ide", ide)
    if (sys != lastSystem) systemText = PhantomCpuBundle.message("widget.system", sys)
    lastIde = ide
    lastSystem = sys

    var breakdown: DoubleArray? = null
    var top = emptyList<TopProcess>()
    if (detailRequests > 0) {
      val d = sampleDetail(wall)
      breakdown = d.breakdown()
      top = d.topProcesses
    }
    else {
      detail = null
    }
    val selfPercent = selfNs * 100.0 / (wallNs.toDouble() * cores)
    publish(CpuSnapshot(ideHistory, systemHistory, ide, sys, high, ideText, systemText, breakdown, top, selfPercent), changed)
  }

  /** Classifies the CPU time of the threads that ran since the previous detailed sample; the first one is a baseline. */
  private fun sampleDetail(wall: Long): Detail {
    val d = detail ?: Detail().also { detail = it }
    val process = os.processCpuTime
    val ids = threads.allThreadIds
    val times = HashMap<Long, Long>(ids.size * 2)
    val busyIds = ArrayList<Long>()
    val busyNs = ArrayList<Long>()
    for (id in ids) {
      val t = threads.getThreadCpuTime(id)
      if (t < 0) continue
      times[id] = t
      val before = d.threadTimes[id] ?: continue
      if (t > before) { busyIds.add(id); busyNs.add(t - before) }
    }
    var gcMs = 0L
    for (gc in gcBeans) gcMs += gc.collectionTime.coerceAtLeast(0)
    val jitMs = if (compilation?.isCompilationTimeMonitoringSupported == true) compilation.totalCompilationTime else 0L

    if (d.process > 0) {
      val byWork = LongArray(Work.entries.size)
      val infos = threads.getThreadInfo(busyIds.toLongArray(), MAX_STACK_DEPTH)
      for (i in infos.indices) {
        val info = infos[i] ?: continue
        byWork[WorkClassifier.classify(info.threadName, info.stackTrace).ordinal] += busyNs[i]
      }
      d.add(WorkClassifier.attribute(process - d.process, byWork, gcMs - d.gcMs, jitMs - d.jitMs, cores))
    }
    d.process = process
    d.threadTimes = times
    d.gcMs = gcMs
    d.jitMs = jitMs
    if (detailRequests > 0 && wall - d.topProcessesAt >= SAMPLE_MS * 1_000_000 / 2) {
      d.topProcesses = readTopProcesses()
      d.topProcessesAt = wall
    }
    else if (detailRequests <= 0) {
      d.topProcesses = emptyList()
    }
    return d
  }

  /** On the sampling thread of a new executor, before its first sample. */
  internal fun resetSamplingState() {
    lastWall = 0L
    lastProcess = 0L
    lastSelf = 0L
    highRun = 0
    ideHistory = IntArray(CpuSnapshot.HISTORY) { -1 }
    systemHistory = IntArray(CpuSnapshot.HISTORY) { -1 }
    ideText = ""
    systemText = ""
    lastIde = -1
    lastSystem = -1
    detail = null
    snapshot = CpuSnapshot.EMPTY
  }

  internal fun publish(s: CpuSnapshot, changed: Boolean) {
    snapshot = s
    for (l in listeners) l.sampled(s, changed)
  }

  private class Detail {
    var process = 0L
    var threadTimes: Map<Long, Long> = emptyMap()
    var gcMs = 0L
    var jitMs = 0L
    var topProcesses = emptyList<TopProcess>()
    var topProcessesAt = 0L
    private val intervals = ArrayDeque<LongArray>()

    fun add(interval: LongArray) {
      intervals.addLast(interval)
      while (intervals.size > BREAKDOWN_SAMPLES) intervals.removeFirst()
    }

    fun breakdown(): DoubleArray? {
      if (intervals.isEmpty()) return null
      val sum = LongArray(Work.entries.size)
      for (iv in intervals) for (i in iv.indices) sum[i] += iv[i]
      val total = sum.sum()
      return DoubleArray(sum.size) { if (total > 0) sum[it].toDouble() / total else 0.0 }
    }
  }

  companion object {
    const val THREAD_NAME = "Phantom CPU Indicator"

    /**
     * Frames read per busy thread: the cost of a stack grows with its depth. The JVM keeps the innermost frames, so in a deeper
     * stack [WorkClassifier.classify] decides by the outermost recognised frame that is left; indexing and analysis frames run
     * through the whole stack of such a thread.
     */
    private const val MAX_STACK_DEPTH = 256
    private const val PS_TIMEOUT_MS = 2_000L
    private val LOG = logger<CpuSampler>()

    private fun percent(fraction: Double): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

    private fun shifted(history: IntArray, value: Int): IntArray {
      val next = IntArray(history.size)
      System.arraycopy(history, 1, next, 0, history.size - 1)
      next[next.size - 1] = value
      return next
    }

    private fun readTopProcesses(): List<TopProcess> {
      val command = when {
        SystemInfo.isMac -> listOf("ps", "-Ao", "pcpu,comm", "-r")
        SystemInfo.isLinux -> listOf("ps", "-Ao", "pcpu,comm", "--sort=-pcpu")
        else -> return emptyList()
      }
      val output = runWithTimeout(command, PS_TIMEOUT_MS) ?: return emptyList()
      return TopProcesses.parse(output, TOP_PROCESSES)
    }

    /**
     * Output of [command], or null when it fails or runs longer than [timeoutMs]. The output goes to a temporary file
     * instead of a pipe: reading a pipe blocks until the process exits, so a hung `ps` would stall the sampling thread forever.
     */
    internal fun runWithTimeout(command: List<String>, timeoutMs: Long): String? {
      val out = try {
        File.createTempFile("phantom-cpu-ps", ".txt")
      }
      catch (e: IOException) {
        LOG.debug(e)
        return null
      }
      try {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out).start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly()
          return null
        }
        return out.readText()
      }
      catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        LOG.debug(e)
        return null
      }
      finally {
        out.delete()
      }
    }
  }
}
