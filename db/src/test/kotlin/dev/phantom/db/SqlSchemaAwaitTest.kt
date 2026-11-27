// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.ApplicationRule
import dev.phantom.db.core.SqlSchema
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.concurrent.thread

class SqlSchemaAwaitTest {
  @Rule @JvmField val appRule = ApplicationRule()

  @Test
  fun `test the wait ends when the load completes, not at the timeout`() {
    val job = CompletableDeferred<Unit>()
    thread { Thread.sleep(50); job.complete(Unit) }
    val started = System.nanoTime()
    SqlSchema.awaitCancellably(job, 10_000)
    assertTrue(job.isCompleted)
    assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
  }

  @Test
  fun `test the wait gives up at the timeout`() {
    val started = System.nanoTime()
    SqlSchema.awaitCancellably(CompletableDeferred<Unit>(), 100)
    assertTrue((System.nanoTime() - started) / 1_000_000 in 100..5_000)
  }

  @Test(expected = ProcessCanceledException::class)
  fun `test a canceled progress ends the wait`() {
    val indicator = ProgressIndicatorBase()
    thread { Thread.sleep(50); indicator.cancel() }
    ProgressManager.getInstance().runProcess({ SqlSchema.awaitCancellably(CompletableDeferred<Unit>(), 10_000) }, indicator)
  }
}
