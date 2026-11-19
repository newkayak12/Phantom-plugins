// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedStackTrace
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** One stack frame: a method of a class, with the line the samples saw first. */
class Frame(val className: String, val method: String, val line: Int) {
  val key: String = "$className.$method"

  /** `com.example.Foo$Bar.run` → `Foo$Bar.run` */
  val shortName: String = className.substringAfterLast('.') + "." + method

  val packageName: String = className.substringBeforeLast('.', "")
}

/** A node of a call tree, weighted by samples (CPU) or bytes (allocations). The root has no frame. */
class CallNode(val frame: Frame?, val parent: CallNode?) {
  var total: Long = 0
    internal set
  var self: Long = 0
    internal set
  private val byKey = LinkedHashMap<String, CallNode>()

  val depth: Int = (parent?.depth ?: -1) + 1

  /** Children, heaviest first. */
  val children: List<CallNode> by lazy { byKey.values.sortedByDescending { it.total } }

  internal fun child(frame: Frame): CallNode = byKey.getOrPut(frame.key) { CallNode(frame, this) }

  /** The node and its ancestors up to (excluding) the root, outermost first. */
  fun path(): List<CallNode> = generateSequence(this) { it.parent }.filter { it.frame != null }.toList().asReversed()

  fun maxDepth(): Int = if (byKey.isEmpty()) depth else byKey.values.maxOf { it.maxDepth() }
}

/** A method's share of all samples: on top of the stack ([self]) and anywhere on it ([total]). */
class MethodStat(val frame: Frame, val self: Long, val total: Long)

class JfrSummary(
  val start: Instant?,
  val duration: Duration,
  val cpuSamples: Long,
  val sampledAllocationBytes: Long,
  val gcCount: Int,
  val gcPauseTotal: Duration,
  val gcPauseMax: Duration,
  /** Average JVM CPU use (user + system) as a fraction of the machine, or null when not recorded. */
  val jvmCpu: Double?,
)

/** What a recording says about where time and memory went. */
class JfrProfile(
  val cpu: CallNode,
  val allocations: CallNode,
  val allocationsByClass: List<Pair<String, Long>>,
  val hotMethods: List<MethodStat>,
  val summary: JfrSummary,
) {
  companion object {
    private const val EXECUTION_SAMPLE = "jdk.ExecutionSample"
    private const val ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample"
    private const val IN_TLAB = "jdk.ObjectAllocationInNewTLAB"
    private const val OUTSIDE_TLAB = "jdk.ObjectAllocationOutsideTLAB"

    /** Reads a `.jfr` file event by event, so a large recording is never held in memory at once. */
    fun read(path: Path): JfrProfile {
      val cpu = CallNode(null, null)
      val allocations = CallNode(null, null)
      val byClass = HashMap<String, Long>()
      val methods = HashMap<String, LongArray>()
      val frames = HashMap<String, Frame>()
      var first: Instant? = null
      var last: Instant? = null
      var samples = 0L
      var allocated = 0L
      var hasAllocationSamples = false
      var gcCount = 0
      var gcTotal = Duration.ZERO
      var gcMax = Duration.ZERO
      var cpuSum = 0.0
      var cpuCount = 0

      RecordingFile(path).use { file ->
        while (file.hasMoreEvents()) {
          val event = file.readEvent()
          val time = event.startTime
          if (first == null || time < first) first = time
          if (last == null || event.endTime > last) last = event.endTime
          when (event.eventType.name) {
            EXECUTION_SAMPLE -> {
              val stack = stackOf(event.stackTrace, frames) ?: continue
              samples++
              add(cpu, stack, 1)
              val seen = HashSet<String>()
              for (frame in stack) {
                val stat = methods.getOrPut(frame.key) { LongArray(2) }
                if (seen.add(frame.key)) stat[1]++
              }
              methods.getValue(stack.last().key)[0]++
            }
            ALLOCATION_SAMPLE -> {
              hasAllocationSamples = true
              allocation(event, event.getLong("weight"), allocations, byClass, frames)?.let { allocated += it }
            }
            IN_TLAB, OUTSIDE_TLAB -> if (!hasAllocationSamples) {
              val size = if (event.eventType.name == IN_TLAB) event.getLong("tlabSize") else event.getLong("allocationSize")
              allocation(event, size, allocations, byClass, frames)?.let { allocated += it }
            }
            "jdk.GarbageCollection" -> {
              gcCount++
              gcTotal += event.getDuration("sumOfPauses")
              event.getDuration("longestPause").let { if (it > gcMax) gcMax = it }
            }
            "jdk.CPULoad" -> {
              cpuSum += event.getFloat("jvmUser") + event.getFloat("jvmSystem")
              cpuCount++
            }
          }
        }
      }
      val hot = methods.map { (key, stat) -> MethodStat(frames.getValue(key), stat[0], stat[1]) }
        .sortedWith(compareByDescending<MethodStat> { it.self }.thenByDescending { it.total })
      val duration = if (first != null && last != null) Duration.between(first, last) else Duration.ZERO
      return JfrProfile(
        cpu, allocations, byClass.entries.sortedByDescending { it.value }.map { it.key to it.value }, hot,
        JfrSummary(first, duration, samples, allocated, gcCount, gcTotal, gcMax, if (cpuCount > 0) cpuSum / cpuCount else null))
    }

    private fun allocation(event: RecordedEvent, bytes: Long, tree: CallNode, byClass: MutableMap<String, Long>, frames: MutableMap<String, Frame>): Long? {
      val type = javaName(event.getClass("objectClass")?.name ?: return null)
      byClass.merge(type, bytes, Long::plus)
      val stack = stackOf(event.stackTrace, frames)
      if (stack != null) add(tree, stack, bytes) else tree.total += bytes
      return bytes
    }

    /** `[I` → `int[]`, `[Ljava.lang.String;` → `java.lang.String[]`; other names stay as they are. */
    internal fun javaName(jvmName: String): String {
      if (!jvmName.startsWith("[")) return jvmName
      val dims = jvmName.takeWhile { it == '[' }.length
      val element = when (val rest = jvmName.substring(dims)) {
        "Z" -> "boolean"; "B" -> "byte"; "C" -> "char"; "S" -> "short"; "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"
        else -> rest.removePrefix("L").removeSuffix(";")
      }
      return element + "[]".repeat(dims)
    }

    /** Outermost frame first; JFR lists the innermost first. Frames are shared so equal methods are one object. */
    private fun stackOf(trace: RecordedStackTrace?, frames: MutableMap<String, Frame>): List<Frame>? {
      val recorded = trace?.frames?.takeIf { it.isNotEmpty() } ?: return null
      return recorded.asReversed().map { f ->
        val className = f.method.type.name
        val method = f.method.name
        frames.getOrPut("$className.$method") { Frame(className, method, f.lineNumber) }
      }
    }

    internal fun add(root: CallNode, stack: List<Frame>, weight: Long) {
      var node = root
      node.total += weight
      for (frame in stack) {
        node = node.child(frame)
        node.total += weight
      }
      node.self += weight
    }
  }
}
