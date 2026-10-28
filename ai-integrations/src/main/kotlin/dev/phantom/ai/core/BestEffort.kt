// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

/** [runCatching] that lets a cancellation through (ProcessCanceledException, CancellationException). */
inline fun <T> bestEffort(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: Throwable) {
    rethrowIfCancellation(e)
    Result.failure(e)
  }

/** For an explicit catch block: throws [e] again when it is a cancellation, returns otherwise. */
fun rethrowIfCancellation(e: Throwable) {
  if (e is ProcessCanceledException || e is CancellationException) throw e
}
