// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.actions

/**
 * Whether the installed Phantom AI Integration has the public API this plugin uses (0.3.1 and later: dev.phantom.ai.api with
 * AiSubagent). An older one loads fine as the dependency but lacks those classes, and opening the office would fail with
 * NoClassDefFoundError (0.3.0 has the API but no subagents).
 */
object ApiCheck {
  const val API_CLASS = "dev.phantom.ai.api.PhantomAiApi"
  const val SUBAGENT_CLASS = "dev.phantom.ai.api.AiSubagent"

  val available: Boolean by lazy { listOf(API_CLASS, SUBAGENT_CLASS).all(::present) }

  private fun present(name: String): Boolean = runCatching { Class.forName(name, false, ApiCheck::class.java.classLoader) }.isSuccess
}
