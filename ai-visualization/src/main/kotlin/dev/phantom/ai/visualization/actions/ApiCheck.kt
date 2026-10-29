// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.actions

/**
 * Whether the installed Phantom AI Integration has the public API (0.3.0 and later). An older one loads fine as the
 * dependency but lacks dev.phantom.ai.api, and opening the office would fail with NoClassDefFoundError.
 */
object ApiCheck {
  const val API_CLASS = "dev.phantom.ai.api.PhantomAiApi"

  val available: Boolean by lazy {
    runCatching { Class.forName(API_CLASS, false, ApiCheck::class.java.classLoader) }.isSuccess
  }
}
