// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CpuSamplerTest {
  @Test
  fun parsesPsOutputSkippingHeaderAndJunk() {
    val output = """
      |%CPU COMM
      |  45,5 /Applications/Some App.app/Contents/MacOS/Some App
      |garbage
      |  12.0 /usr/bin/java
      |   3.1 kernel_task
    """.trimMargin()
    assertEquals(
      listOf(TopProcess("Some App", 45.5), TopProcess("java", 12.0)),
      TopProcesses.parse(output, 2),
    )
  }

  @Test
  fun parsesEmptyOutput() {
    assertEquals(emptyList<TopProcess>(), TopProcesses.parse("", 3))
  }

  @Test
  fun hungCommandTimesOut() {
    assumeTrue(File("/bin/sleep").exists())
    val start = System.nanoTime()
    assertNull(CpuSampler.runWithTimeout(listOf("/bin/sleep", "30"), 200))
    assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
  }

  @Test
  fun commandOutputIsRead() {
    assumeTrue(File("/bin/echo").exists())
    assertEquals("hi\n", CpuSampler.runWithTimeout(listOf("/bin/echo", "hi"), 5_000))
  }

  @Test
  fun resetClearsSnapshot() {
    val sampler = CpuSampler()
    val s = CpuSnapshot(IntArray(CpuSnapshot.HISTORY) { 50 }, IntArray(CpuSnapshot.HISTORY) { 60 }, 50, 60, true, "a", "b",
                        null, listOf(TopProcess("x", 1.0)), 0.1)
    sampler.publish(s, true)
    assertSame(s, sampler.snapshot)
    sampler.resetSamplingState()
    assertSame(CpuSnapshot.EMPTY, sampler.snapshot)
  }
}
