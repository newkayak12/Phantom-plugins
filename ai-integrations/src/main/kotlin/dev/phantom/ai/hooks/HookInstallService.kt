// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.rethrowIfCancellation
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SystemDirs
import java.nio.file.Path

/** Installs the hook scripts and agent config for the System default dirs and every account dir. */
@Service(Service.Level.APP)
class HookInstallService {
  /** System default config dir. A sandbox/probe redirects it with -Dphantom.ai.claudeDir / -Dphantom.ai.codexDir. */
  fun systemDir(kind: AgentKind): Path = SystemDirs.dir(kind)

  /** Installs for [provider] into [dir]; false when its config file is malformed (left untouched). */
  fun ensureInstalled(provider: AgentKind, dir: Path, scriptDir: Path = HookScripts.defaultDir()): Boolean {
    val scripts = HookScripts.write(scriptDir)
    return when (provider) {
      AgentKind.CLAUDE -> ClaudeHookInstaller.install(dir, scripts)
      AgentKind.CODEX -> CodexHookInstaller.install(dir, scripts)
    }
  }

  fun installAll() {
    val state = AiState.getInstance()
    for (kind in AgentKind.entries) {
      val dirs = listOf(systemDir(kind)) + state.accountsOf(kind).mapNotNull { it.dir?.takeIf(String::isNotBlank)?.let { d -> Path.of(d) } }
      for (dir in dirs.distinct()) {
        try {
          if (!ensureInstalled(kind, dir)) {
            AiNotify.error(LOG, null, PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, "settings are not valid JSON"), null)
          }
        } catch (e: Exception) {
          rethrowIfCancellation(e)
          AiNotify.error(LOG, null, PhantomAiBundle.message("notify.hooks.failed", kind.displayName, dir, e.message ?: e.toString()), e)
        }
      }
    }
  }

  companion object {
    const val CLAUDE_DIR_PROP = SystemDirs.CLAUDE_DIR_PROP
    const val CODEX_DIR_PROP = SystemDirs.CODEX_DIR_PROP
    private val LOG = logger<HookInstallService>()
    fun getInstance(): HookInstallService = ApplicationManager.getApplication().getService(HookInstallService::class.java)
  }
}

/**
 * App start: installs the hooks (skipped in unit-test mode so tests never touch ~/.claude or ~/.codex) and starts Remote
 * Control when it is turned on. Off (the default), the server and its Netty classes are not loaded at startup.
 */
class HookStartup : AppLifecycleListener {
  override fun appStarted() {
    val app = ApplicationManager.getApplication()
    if (app.isUnitTestMode) return
    app.executeOnPooledThread { HookInstallService.getInstance().installAll() }
    bestEffort {
      if (dev.phantom.ai.remote.RemoteControlSettings.getInstance().state.enabled) dev.phantom.ai.remote.RemoteControlServer.getInstance().applySettings()
    }
  }
}
