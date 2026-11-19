// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.profiler

import dev.phantom.profiler.gradle.GradleProfileInitScript
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

class GradleProfilingTest : TestCase() {
  fun testInitScriptRecordsEveryForkedJvm() {
    val script = GradleProfileInitScript.script(Path.of("/tmp/it's here"))
    assertTrue(script, script.contains("filename=' + '/tmp/it\\'s here' + '/' + kind + '-%p.jfr'"))
    assertTrue(script.contains("tasks.withType(Test).configureEach"))
    assertTrue(script.contains("outputs.upToDateWhen { false }"))
    assertTrue(script.contains("tasks.withType(JavaExec).configureEach { jvmArgs(phantomJfr('run')) }"))
  }

  fun testRecordingsInAFolderLargestFirst() {
    val dir = Files.createTempDirectory("phantom-jfr")
    try {
      Files.write(dir.resolve("test-1.jfr"), ByteArray(10))
      Files.write(dir.resolve("test-2.jfr"), ByteArray(30))
      Files.write(dir.resolve("empty.jfr"), ByteArray(0))
      Files.write(dir.resolve("notes.txt"), ByteArray(50))
      assertEquals(listOf("test-2.jfr", "test-1.jfr"), Recordings.recordingsIn(dir).map { it.fileName.toString() })
    }
    finally {
      dir.toFile().deleteRecursively()
    }
  }
}
