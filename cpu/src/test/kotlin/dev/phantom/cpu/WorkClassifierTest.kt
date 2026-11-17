// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkClassifierTest {
  /** Innermost frame first, like Thread.getStackTrace. */
  private fun stack(vararg classes: String) = classes.map { StackTraceElement(it, "run", null, -1) }.toTypedArray()

  private val pool = arrayOf("java.util.concurrent.ThreadPoolExecutor\$Worker", "java.lang.Thread")

  @Test
  fun highlightingPassThatReadsAnIndexIsAnalysis() {
    val s = stack("com.intellij.util.indexing.FileBasedIndexImpl", "com.intellij.util.indexing.UnindexedFilesIndexer",
                  "org.jetbrains.kotlin.analysis.api.fir.KaFirSession",
                  "com.intellij.codeInsight.daemon.impl.PassExecutorService\$ScheduledPass", *pool)
    assertEquals(Work.ANALYSIS, WorkClassifier.classify("DefaultDispatcher-worker-3", s))
  }

  @Test
  fun indexerThatBuildsPsiIsIndexing() {
    val s = stack("com.intellij.psi.impl.source.tree.LazyParseableElement", "org.jetbrains.kotlin.fir.Something",
                  "com.intellij.util.indexing.IndexUpdateRunner", *pool)
    assertEquals(Work.INDEXING, WorkClassifier.classify("ApplicationImpl pooled thread 7", s))
  }

  @Test
  fun indexWriterThreadsAreIndexingByName() {
    for (name in listOf("Stubs Writer #1", "IdIndex Writer #2", "Trigram.Index Writer #1", "Aux Index Writer #3")) {
      assertEquals(name, Work.INDEXING, WorkClassifier.classify(name, stack(*pool)))
    }
  }

  @Test
  fun scanningHealthCheckIsIndexing() {
    val s = stack("com.intellij.util.indexing.roots.IndexableFilesIterationMethods",
                  "com.intellij.util.indexing.projectFilter.ProjectIndexableFilesFilterHealthCheck", *pool)
    assertEquals(Work.INDEXING, WorkClassifier.classify("DefaultDispatcher-worker-1", s))
  }

  @Test
  fun unknownWorkIsOther() {
    assertEquals(Work.OTHER, WorkClassifier.classify("AWT-EventQueue-0", stack("javax.swing.RepaintManager", *pool)))
    assertEquals(Work.OTHER, WorkClassifier.classify("x", emptyArray()))
  }

  @Test
  fun vmTimeGoesToGcAndJitByTheirShareAndTheRestToOther() {
    val java = LongArray(Work.entries.size).apply { this[Work.INDEXING.ordinal] = 600; this[Work.ANALYSIS.ordinal] = 200 }
    // 1000 ns of process time, 800 on Java threads: 200 on VM threads, GC:JIT = 3:1
    val r = WorkClassifier.attribute(1000, java, gcMs = 3, jitMs = 1, cores = 8)
    assertArrayEquals(longArrayOf(600, 200, 150, 50, 0), r)
  }

  @Test
  fun gcIsCappedAtWhatAllCoresCouldDoDuringIt() {
    val r = WorkClassifier.attribute(10_000_000_000, LongArray(Work.entries.size), gcMs = 1, jitMs = 0, cores = 4)
    assertEquals(4_000_000, r[Work.GC.ordinal])
    assertEquals(10_000_000_000 - 4_000_000, r[Work.OTHER.ordinal])
  }

  @Test
  fun noVmTimeWhenThreadsAccountForEverything() {
    val java = LongArray(Work.entries.size).apply { this[Work.OTHER.ordinal] = 1200 }
    val r = WorkClassifier.attribute(1000, java, gcMs = 5, jitMs = 5, cores = 8)
    assertArrayEquals(longArrayOf(0, 0, 0, 0, 1200), r)
  }

  @Test
  fun psOutputIsParsed() {
    val out = """
      |  %CPU COMM
      | 87.5 /Applications/Phantom.app/Contents/MacOS/phantom
      | 12,0 /System/Library/Frameworks/WebKit.framework/Versions/A/XPCServices/com.apple.WebKit.WebContent.xpc/Contents/MacOS/com.apple.WebKit.WebContent
      |  3.1 Google Chrome Helper (Renderer)
      |  1.0 kernel_task
    """.trimMargin()
    assertEquals(
      listOf(TopProcess("phantom", 87.5), TopProcess("com.apple.WebKit.WebContent", 12.0), TopProcess("Google Chrome Helper (Renderer)", 3.1)),
      TopProcesses.parse(out, 3))
  }
}
