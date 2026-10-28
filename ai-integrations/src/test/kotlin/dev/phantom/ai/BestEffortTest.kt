// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.rethrowIfCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class BestEffortTest {
  @Test fun processCanceledIsRethrown() {
    val pce = ProcessCanceledException()
    val thrown = runCatching { bestEffort<Int> { throw pce } }.exceptionOrNull()
    assertSame(pce, thrown)
  }

  @Test fun cancellationIsRethrown() {
    val ce = CancellationException("stop")
    assertSame(ce, runCatching { bestEffort<Int> { throw ce } }.exceptionOrNull())
    assertSame(ce, runCatching { rethrowIfCancellation(ce) }.exceptionOrNull())
  }

  @Test fun otherFailureIsAResult() {
    val boom = IllegalStateException("boom")
    val r = bestEffort<Int> { throw boom }
    assertTrue(r.isFailure)
    assertSame(boom, r.exceptionOrNull())
    rethrowIfCancellation(boom)
  }

  @Test fun successIsAResult() {
    assertEquals(Result.success(7), bestEffort { 7 })
  }
}
