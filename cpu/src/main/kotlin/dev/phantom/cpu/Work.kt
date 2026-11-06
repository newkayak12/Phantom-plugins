// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.PhantomCpuBundle"

object PhantomCpuBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** What a slice of the IDE's CPU time was spent on. */
enum class Work(val key: String) {
  INDEXING("category.indexing"),
  ANALYSIS("category.analysis"),
  GC("category.gc"),
  JIT("category.jit"),
  OTHER("category.other");

  val displayName: String get() = PhantomCpuBundle.message(key)
}

/**
 * Tells what a busy thread is doing from its name and stack.
 *
 * Thread names alone say little: in the freeze dumps almost all work runs on `DefaultDispatcher-worker-N` and
 * `ApplicationImpl pooled thread N`. Only the index writer threads (`Stubs Writer #N`, `IdIndex Writer #N`,
 * `Trigram.Index Writer #N`, `Aux Index Writer #N`) are named for their job. So the stack decides, and the outermost
 * recognized frame wins: a highlighting pass that reads an index is analysis, an indexer that builds PSI is indexing.
 */
object WorkClassifier {
  private val indexingFrames = arrayOf(
    "com.intellij.util.indexing.UnindexedFilesIndexer",
    "com.intellij.util.indexing.UnindexedFilesScanner",
    "com.intellij.util.indexing.IndexUpdateRunner",
    "com.intellij.util.indexing.contentQueue.",
    "com.intellij.util.indexing.projectFilter.",
    "com.intellij.util.indexing.roots.",
    "com.intellij.util.indexing.dependenciesCache.",
    "com.intellij.util.indexing.diagnostic.",
  )

  private val analysisFrames = arrayOf(
    "com.intellij.codeInsight.daemon.",
    "com.intellij.codeInsight.highlighting.",
    "com.intellij.codeInsight.completion.",
    "com.intellij.codeInsight.hints.",
    "com.intellij.codeInspection.",
    "org.jetbrains.kotlin.analysis.",
    "org.jetbrains.kotlin.fir.",
    "org.jetbrains.kotlin.idea.highlighter.",
    "org.jetbrains.kotlin.idea.base.highlighting.",
  )

  fun classify(threadName: String, stack: Array<StackTraceElement>): Work {
    if (threadName.contains("Index Writer") || threadName.startsWith("Stubs Writer")) return Work.INDEXING
    for (i in stack.indices.reversed()) {
      val cls = stack[i].className
      if (indexingFrames.any { cls.startsWith(it) }) return Work.INDEXING
      if (analysisFrames.any { cls.startsWith(it) }) return Work.ANALYSIS
    }
    return Work.OTHER
  }

  /**
   * Splits one interval of process CPU time into work kinds.
   *
   * [javaByWork] is the CPU time of Java threads, already classified. GC and JIT run on VM threads that
   * ThreadMXBean does not list, so they come out of the rest (`processNs - sum(javaByWork)`), in proportion to the
   * GC and compilation times the MXBeans report, each at most what all cores could do in that time. What is left is OTHER.
   */
  fun attribute(processNs: Long, javaByWork: LongArray, gcMs: Long, jitMs: Long, cores: Int): LongArray {
    val result = javaByWork.copyOf(Work.entries.size)
    val vmNs = (processNs - javaByWork.sum()).coerceAtLeast(0)
    val gcJitMs = gcMs + jitMs
    var gc = 0L
    var jit = 0L
    if (gcJitMs > 0) {
      gc = minOf(vmNs * gcMs / gcJitMs, gcMs * 1_000_000 * cores)
      jit = minOf(vmNs * jitMs / gcJitMs, jitMs * 1_000_000 * cores)
    }
    result[Work.GC.ordinal] += gc
    result[Work.JIT.ordinal] += jit
    result[Work.OTHER.ordinal] += vmNs - gc - jit
    return result
  }
}

/** A process from `ps`, CPU in percent of one core as `ps` reports it. */
data class TopProcess(val name: String, val cpu: Double)

object TopProcesses {
  /** Parses `ps -Ao pcpu,comm` output: a header line, then `  12.3 /path/to/command with spaces`. */
  fun parse(output: String, limit: Int): List<TopProcess> =
    output.lineSequence().drop(1).mapNotNull { line ->
      val trimmed = line.trim()
      val space = trimmed.indexOfFirst { it.isWhitespace() }
      if (space <= 0) return@mapNotNull null
      val cpu = trimmed.substring(0, space).replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
      val command = trimmed.substring(space).trim()
      if (command.isEmpty()) null else TopProcess(command.substringAfterLast('/'), cpu)
    }.take(limit).toList()
}
