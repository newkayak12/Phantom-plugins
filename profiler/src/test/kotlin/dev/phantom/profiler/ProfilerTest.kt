// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import jdk.jfr.Recording
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ProfilerTest : BasePlatformTestCase() {
  /** Busy work the sampler must see, with allocations it must attribute. */
  private fun burn(until: Long): Long {
    var acc = 0L
    val keep = ArrayList<IntArray>()
    while (System.nanoTime() < until) {
      for (i in 0 until 10_000) acc += (i * 31L) xor acc
      keep.add(IntArray(4096))
      if (keep.size > 64) keep.clear()
    }
    return acc + keep.size
  }

  private fun record(): Path {
    val file = Files.createTempFile("phantom-profiler", ".jfr")
    Recording().use { r ->
      r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
      r.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s")
      r.enable("jdk.GarbageCollection")
      r.start()
      burn(System.nanoTime() + 1_500_000_000L)
      System.gc()
      r.stop()
      r.dump(file)
    }
    return file
  }

  fun testRecordingIsReadIntoCallTreeAndMethods() {
    val file = record()
    try {
      val profile = JfrProfile.read(file)
      assertTrue("no CPU samples", profile.cpu.total > 0)
      assertEquals(profile.cpu.total, profile.summary.cpuSamples)

      // the burn frame is on the main path and owns most of its own time
      val burn = profile.hotMethods.firstOrNull { it.frame.method == "burn" && it.frame.className == ProfilerTest::class.java.name }
      assertNotNull(profile.hotMethods.take(5).map { it.frame.key }.toString(), burn)
      assertTrue(burn!!.total * 2 > profile.cpu.total)
      assertTrue(burn.frame.line > 0)

      fun find(node: CallNode): CallNode? = if (node.frame?.method == "burn") node else node.children.firstNotNullOfOrNull(::find)
      val node = find(profile.cpu)!!
      assertTrue(node.path().any { it.frame!!.method.startsWith("testRecordingIsRead") })
      assertEquals(node.total, node.self + node.children.sumOf { it.total })

      assertTrue("no allocations", profile.allocations.total > 0)
      assertTrue(profile.allocationsByClass.toString(), profile.allocationsByClass.any { it.first == "int[]" })
      assertTrue(profile.summary.gcCount >= 1)
    }
    finally {
      Files.deleteIfExists(file)
    }
  }

  fun testProfiledRunGetsFlightRecorderOptions() {
    val configuration = ApplicationConfiguration("Reservation App", project)
    val parameters = JavaParameters()
    ProfileProgramRunner().patch(parameters, null, configuration, true)
    val options = parameters.vmParametersList.parameters
    val start = options.single { it.startsWith("-XX:StartFlightRecording=") }
    assertTrue(start, "dumponexit=true" in start && "settings=profile" in start)
    val file = Path.of(start.substringAfter("filename="))
    assertEquals(Recordings.directory, file.parent)
    assertTrue(file.fileName.toString(), file.fileName.toString().startsWith("Reservation_App-"))
    Recordings.started(configuration, 42L)
    assertEquals(file, Recordings.take(42L))
    assertNull(Recordings.take(42L))

    // a configuration that starts JFR itself keeps its own options
    val own = JavaParameters().apply { vmParametersList.add("-XX:StartFlightRecording=filename=/tmp/x.jfr") }
    ProfileProgramRunner().patch(own, null, configuration, true)
    assertEquals(1, own.vmParametersList.parameters.count { it.startsWith("-XX:StartFlightRecording") })
  }

  fun testSavedRecordingsCarrySizeAndTime() {
    val file = Recordings.newFile("Saved Size")
    try {
      Files.write(file, ByteArray(1234))
      val saved = Recordings.list().single { it.path == file }
      assertEquals(1234L, saved.size)
      assertEquals(Files.getLastModifiedTime(file).toMillis(), saved.modified)
    }
    finally {
      Files.deleteIfExists(file)
    }
  }

  fun testRunnerOnlyTakesTheProfileExecutor() {
    val configuration = ApplicationConfiguration("App", project)
    assertTrue(ProfileProgramRunner().canRun(ProfileExecutor.ID, configuration))
    assertFalse(ProfileProgramRunner().canRun("Run", configuration))
  }

  fun testFlameGraphLayoutAndZoom() {
    val root = CallNode(null, null)
    val main = Frame("app.Main", "main", 1)
    val a = Frame("app.Main", "a", 2)
    val b = Frame("app.Main", "b", 3)
    // 3 samples in main→a, 1 in main→b
    repeat(3) { JfrProfile.add(root, listOf(main, a), 1) }
    JfrProfile.add(root, listOf(main, b), 1)

    val graph = FlameGraph(root, { "$it" }, { true }, {})
    val boxes = graph.layout(400)
    val byName = boxes.associate { it.second.frame!!.method to it.first }
    assertEquals(400, byName.getValue("main").width + 1)
    assertEquals(300, byName.getValue("a").width + 1)
    assertEquals(300, byName.getValue("b").x)
    assertTrue(byName.getValue("a").y > byName.getValue("main").y)

    // zooming into b: main stays above as a full-width ancestor, b spans the width
    graph.zoom(boxes.first { it.second.frame === b }.second)
    val zoomed = graph.layout(400).associate { it.second.frame!!.method to it.first }
    assertEquals(400, zoomed.getValue("b").width + 1)
    assertEquals(400, zoomed.getValue("main").width)
    assertFalse("a" in zoomed)
  }

  fun testRecordRunningJvmWithJcmd() {
    val dir = Files.createTempDirectory("phantom-attach")
    val source = dir.resolve("Busy.java")
    Files.writeString(source, """
      public class Busy {
        public static void main(String[] args) throws Exception {
          long acc = 0; long end = System.currentTimeMillis() + 60_000;
          while (System.currentTimeMillis() < end) { for (int i = 0; i < 100_000; i++) acc += i ^ acc; if (acc == 42) System.out.println(acc); }
        }
      }
    """.trimIndent())
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val process = ProcessBuilder(java, source.toString()).redirectErrorStream(true).start()
    try {
      var jvm: LocalJvm? = null
      for (i in 0 until 100) {
        jvm = JvmAttach.list().firstOrNull { it.pid == process.pid() }
        if (jvm != null) break
        Thread.sleep(100)
      }
      assertNotNull("jcmd -l does not list the started JVM", jvm)
      assertFalse(JvmAttach.list().any { it.pid == ProcessHandle.current().pid() })

      JvmAttach.start(jvm!!.pid)
      assertTrue(JvmAttach.isRecording(jvm.pid))
      Thread.sleep(1500)
      val file = JvmAttach.stop(jvm)
      try {
        assertFalse(JvmAttach.isRecording(jvm.pid))
        val profile = JfrProfile.read(file)
        assertTrue(profile.hotMethods.take(5).map { it.frame.key }.toString(), profile.hotMethods.any { it.frame.className == "Busy" })
      }
      finally {
        Files.deleteIfExists(file)
      }
    }
    finally {
      process.destroyForcibly()
      dir.toFile().deleteRecursively()
    }
  }

  fun testArrayClassNames() {
    assertEquals("int[]", JfrProfile.javaName("[I"))
    assertEquals("java.lang.String[][]", JfrProfile.javaName("[[Ljava.lang.String;"))
    assertEquals("java.util.HashMap", JfrProfile.javaName("java.util.HashMap"))
  }

  fun testLabelsAreShortenedToFit() {
    assertEquals("Main.run", FlameGraph.fit("Main.run", 100) { it.length * 10 })
    assertEquals("Main…", FlameGraph.fit("Main.run", 50) { it.length * 10 })
    assertNull(FlameGraph.fit("Main.run", 5) { it.length * 10 })
  }
}
