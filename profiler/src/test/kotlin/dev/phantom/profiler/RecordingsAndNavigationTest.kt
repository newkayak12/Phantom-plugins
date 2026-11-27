// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecordingsAndNavigationTest : BasePlatformTestCase() {
  fun testGradleSubfolderRecordingsAreListedAndDeletedWithTheirFolder() {
    val root = Files.createTempDirectory("phantom-recordings")
    val top = Files.write(root.resolve("app.jfr"), byteArrayOf(1))
    val run = Files.createDirectories(root.resolve("build-20261127-120000"))
    val test = Files.write(run.resolve("test-1.jfr"), byteArrayOf(1, 2))
    assertSameElements(Recordings.list(root).map { it.path }, top, test)

    Recordings.delete(root, test)
    assertFalse(Files.exists(run))
    assertSameElements(Recordings.list(root).map { it.path }, top)
    Recordings.delete(root, top)
    assertTrue(Files.exists(root))
  }

  fun testRecordingsAreKeptPerExecutionNotPerConfiguration() {
    val config = ApplicationConfiguration("Same", project)
    Recordings.prepare(config, JavaParameters())
    Recordings.started(config, 1L)
    Recordings.prepare(config, JavaParameters())
    Recordings.started(config, 2L)
    val first = Recordings.take(1L)
    val second = Recordings.take(2L)
    assertNotNull(first)
    assertNotNull(second)
    assertNull(Recordings.take(1L))
  }

  fun testInnerAndLambdaFramesResolveToTheirSource() {
    assertEquals(listOf("a.Outer.Inner", "a.Outer"), FrameNavigation.sourceClassCandidates("a.Outer\$Inner"))
    assertEquals(listOf("a.Outer"), FrameNavigation.sourceClassCandidates("a.Outer\$\$Lambda/0x0000000801001234"))
    assertEquals(listOf("a.Outer"), FrameNavigation.sourceClassCandidates("a.Outer\$1"))
    assertEquals("foo", FrameNavigation.sourceMethodName("lambda\$foo\$0"))
    assertEquals("foo", FrameNavigation.sourceMethodName("foo\$lambda\$0"))
    assertEquals("run", FrameNavigation.sourceMethodName("run"))

    myFixture.addFileToProject("a/Outer.java", "package a; public class Outer { public class Inner {} void foo() { Runnable r = () -> {}; } }")
    val own = com.intellij.openapi.application.ReadAction.compute<Set<String>, RuntimeException> {
      FrameNavigation.projectClasses(project, listOf("a.Outer\$Inner", "a.Outer\$\$Lambda/0x1", "java.lang.String", "b.Missing"))
    }
    assertSameElements(own, "a.Outer\$Inner", "a.Outer\$\$Lambda/0x1")
    assertFalse(FrameNavigation.mayBeProjectClass("java.lang.Thread"))
  }

  fun testJfrCheckOutputIsParsed() {
    assertTrue(JvmAttach.isRunning("12345:\nRecording 1: name=phantom maxsize=250.0MB (running)\n"))
    assertFalse(JvmAttach.isRunning("12345:\nRecording 1: name=phantom maxsize=250.0MB (stopped)\n"))
    assertFalse(JvmAttach.isRunning("12345:\nRecording 2: name=phantomX (running)\n"))
    assertFalse(JvmAttach.isRunning("12345:\nNo available recordings.\n"))
  }

  fun testWhenReadyWaitsWithoutBlockingAndGivesUp() {
    val checks = AtomicInteger()
    val done = CountDownLatch(1)
    whenReady(30, { checks.incrementAndGet() >= 3 }) { done.countDown() }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(3, checks.get())

    val gaveUp = CountDownLatch(1)
    whenReady(2, { false }) { gaveUp.countDown() }
    assertTrue(gaveUp.await(5, TimeUnit.SECONDS))
  }
}
