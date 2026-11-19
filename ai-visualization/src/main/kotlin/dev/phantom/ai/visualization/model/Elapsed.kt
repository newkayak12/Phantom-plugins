// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.model

/** "45s", "12m", "1h 5m", "2d 3h": how long a session has run. */
object Elapsed {
  fun format(startedAt: Long, now: Long): String {
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    return when {
      s < 60 -> "${s}s"
      s < 3600 -> "${s / 60}m"
      s < 86400 -> "${s / 3600}h ${s % 3600 / 60}m"
      else -> "${s / 86400}d ${s % 86400 / 3600}h"
    }
  }
}
