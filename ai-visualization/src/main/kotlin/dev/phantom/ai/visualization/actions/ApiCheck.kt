// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization.actions

/**
 * Whether the installed Phantom AI Integration has the public API this plugin uses (0.3.8 and later: dev.phantom.ai.api with
 * AiSubagent and the sessions' and subagents' activity). An older one loads fine as the dependency but lacks those, and opening
 * the office would fail with NoClassDefFoundError or NoSuchMethodError (0.3.0 has the API but no subagents, 0.3.1 to 0.3.5 no activity).
 */
object ApiCheck {
  const val API_CLASS = "dev.phantom.ai.api.PhantomAiApi"
  const val SUBAGENT_CLASS = "dev.phantom.ai.api.AiSubagent"
  const val SESSION_CLASS = "dev.phantom.ai.api.AiSession"
  const val ACTIVITY_GETTER = "getActivity"

  val available: Boolean by lazy {
    listOf(API_CLASS, SUBAGENT_CLASS).all(::present) && listOf(SESSION_CLASS, SUBAGENT_CLASS).all { hasMethod(it, ACTIVITY_GETTER) }
  }

  private fun present(name: String): Boolean = runCatching { Class.forName(name, false, ApiCheck::class.java.classLoader) }.isSuccess

  private fun hasMethod(cls: String, method: String): Boolean =
    runCatching { Class.forName(cls, false, ApiCheck::class.java.classLoader).getMethod(method) }.isSuccess
}
