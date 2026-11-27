// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

@PublishedApi
internal val bestEffortLog: Logger = Logger.getInstance("dev.phantom.ai.core.BestEffort")

/**
 * [runCatching] that lets a cancellation through (ProcessCanceledException, CancellationException). Other failures are
 * logged at debug level, so a lookup that keeps failing can be traced without filling idea.log.
 */
inline fun <T> bestEffort(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: Throwable) {
    rethrowIfCancellation(e)
    bestEffortLog.debug("best-effort call failed", e)
    Result.failure(e)
  }

/** For an explicit catch block: throws [e] again when it is a cancellation, returns otherwise. */
fun rethrowIfCancellation(e: Throwable) {
  if (e is ProcessCanceledException || e is CancellationException) throw e
}
