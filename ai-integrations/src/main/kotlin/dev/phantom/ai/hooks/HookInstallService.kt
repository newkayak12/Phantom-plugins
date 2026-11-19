// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.hooks

import dev.phantom.ai.core.bestEffort
import dev.phantom.ai.core.rethrowIfCancellation
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiNotify
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.HookConsent
import dev.phantom.ai.core.PhantomAiBundle
import dev.phantom.ai.core.SystemDirs
import java.nio.file.Path

/**
 * Installs the hook scripts and agent config for every account dir (folders Phantom made itself) and, only once the user
 * allowed it ([HookConsent.ALLOWED]), for the System default dirs: ~/.claude/settings.json and ~/.codex/hooks.json
 * belong to Claude Code and Codex, so they are not changed without asking.
 */
@Service(Service.Level.APP)
class HookInstallService(private val stateProvider: () -> AiState = { AiState.getInstance() }) {
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

  /** The files outside Phantom's own folders that the hooks go into, as the consent prompt lists them. */
  fun systemFiles(): List<Path> = AgentKind.entries.map { systemDir(it).resolve(configFile(it)) }

  fun consent(): HookConsent = stateProvider().state.hookConsent

  /**
   * Records the user's answer. Allowed installs into the System default dirs now; refused takes out what an earlier
   * allow put there. Runs the file work off the EDT.
   */
  fun setConsent(consent: HookConsent) {
    val before = consent()
    stateProvider().state.hookConsent = consent
    if (consent == before) return
    val work = when (consent) {
      HookConsent.ALLOWED -> ::installAll
      HookConsent.DECLINED -> ::uninstallSystem
      HookConsent.ASK -> return
    }
    val app = ApplicationManager.getApplication()
    if (app == null || app.isUnitTestMode) work() else app.executeOnPooledThread { work() }
  }

  /** Takes the plugin's hooks and status line out of the System default config files, keeping everything else. */
  fun uninstallSystem() {
    for (kind in AgentKind.entries) {
      val dir = systemDir(kind)
      try {
        val ok = when (kind) {
          AgentKind.CLAUDE -> ClaudeHookInstaller.uninstall(dir)
          AgentKind.CODEX -> CodexHookInstaller.uninstall(dir)
        }
        if (!ok) AiNotify.error(LOG, null, PhantomAiBundle.message("notify.hooks.remove.failed", kind.displayName, dir, "settings are not valid JSON"), null)
      } catch (e: Exception) {
        rethrowIfCancellation(e)
        AiNotify.error(LOG, null, PhantomAiBundle.message("notify.hooks.remove.failed", kind.displayName, dir, e.message ?: e.toString()), e)
      }
    }
  }

  fun installAll() {
    val state = stateProvider()
    val allowed = state.state.hookConsent == HookConsent.ALLOWED
    for (kind in AgentKind.entries) {
      val dirs = listOfNotNull(systemDir(kind).takeIf { allowed }) + state.accountsOf(kind).mapNotNull { it.dir?.takeIf(String::isNotBlank)?.let { d -> Path.of(d) } }
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
    fun configFile(kind: AgentKind): String = when (kind) {
      AgentKind.CLAUDE -> "settings.json"
      AgentKind.CODEX -> "hooks.json"
    }

    const val CLAUDE_DIR_PROP = SystemDirs.CLAUDE_DIR_PROP
    const val CODEX_DIR_PROP = SystemDirs.CODEX_DIR_PROP
    private val LOG = logger<HookInstallService>()
    fun getInstance(): HookInstallService = ApplicationManager.getApplication().getService(HookInstallService::class.java)
  }
}

/**
 * App start: installs the hooks (skipped in unit-test mode so tests never touch ~/.claude or ~/.codex), asks once
 * whether the System default config files may be changed, and starts Remote Control when it is turned on. Off (the
 * default), the server and its Netty classes are not loaded at startup.
 */
class HookStartup : AppLifecycleListener {
  override fun appStarted() {
    val app = ApplicationManager.getApplication()
    if (app.isUnitTestMode) return
    val service = HookInstallService.getInstance()
    app.executeOnPooledThread { service.installAll() }
    if (service.consent() == HookConsent.ASK) bestEffort { HookConsentPrompt.show(service) }
    bestEffort {
      if (dev.phantom.ai.remote.RemoteControlSettings.getInstance().state.enabled) dev.phantom.ai.remote.RemoteControlServer.getInstance().applySettings()
    }
  }
}

/**
 * The one-time question: which files the hooks go into, with Allow and Don't modify. Unanswered, it comes back at the
 * next start; Settings | Tools | Phantom AI Integration changes the answer later.
 */
object HookConsentPrompt {
  fun show(service: HookInstallService = HookInstallService.getInstance()) {
    val files = service.systemFiles().joinToString("<br>") { "<code>${StringUtil.escapeXmlEntities(it.toString())}</code>" }
    NotificationGroupManager.getInstance().getNotificationGroup(AiNotify.GROUP)
      .createNotification(PhantomAiBundle.message("hooks.consent.title"), PhantomAiBundle.message("hooks.consent.text", files), NotificationType.INFORMATION)
      .setImportant(true)
      .addAction(NotificationAction.createSimpleExpiring(PhantomAiBundle.message("hooks.consent.allow")) { service.setConsent(HookConsent.ALLOWED) })
      .addAction(NotificationAction.createSimpleExpiring(PhantomAiBundle.message("hooks.consent.decline")) { service.setConsent(HookConsent.DECLINED) })
      .notify(null)
  }
}

/** Uninstalling the plugin (not updating it) takes its hooks out of the System default config files. */
class HookUninstall : DynamicPluginListener {
  override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
    if (isUpdate || pluginDescriptor.pluginId.idString != PLUGIN_ID) return
    if (HookInstallService.getInstance().consent() == HookConsent.ALLOWED) bestEffort { HookInstallService.getInstance().uninstallSystem() }
  }

  companion object {
    const val PLUGIN_ID = "dev.phantom.ai"
  }
}
