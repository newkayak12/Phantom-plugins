// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import org.jetbrains.annotations.TestOnly

/**
 * Set once this plugin starts unloading (disabled or updated without a restart; see DetachedSessionsUnload). From then on
 * timers, pooled tasks and disposables look no service up: the classloader is going, and a lookup is a SEVERE error
 * that keeps the plugin from unloading.
 */
object Unloading {
  @Volatile var inProgress = false
    private set

  fun start() {
    inProgress = true
  }

  @TestOnly fun reset() {
    inProgress = false
  }
}
