// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.accounts.AccountsService
import dev.phantom.ai.accounts.LoginTerminal
import dev.phantom.ai.core.AgentKind
import dev.phantom.ai.core.AiState
import java.nio.file.Files

/**
 * A failed hook install on account add reaches the real notification bus (the registered "AI Integrations" group).
 * The login terminal is a recording fake: this test never starts a real claude/codex CLI or a login flow.
 */
class AccountsNotifyInIdeTest : BasePlatformTestCase() {
  fun testFailedHookInstallPostsOneErrorNotification() {
    val base = Files.createTempDirectory("accnotify").toRealPath()
    val seen = mutableListOf<Notification>()
    val logins = mutableListOf<String>()
    val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
    connection.subscribe(Notifications.TOPIC, object : Notifications {
      override fun notify(notification: Notification) {
        if (notification.groupId == "AI Integrations") seen.add(notification)
      }
    })
    try {
      val service = AccountsService(
        baseDir = base,
        installer = { _, _ -> false },
        terminal = LoginTerminal { command, _, _ -> logins.add(command) },
        stateProvider = { AiState() },
      )
      val account = service.add(AgentKind.CLAUDE)
      assertEquals(1, seen.size)
      val n = seen.single()
      assertEquals("AI Integrations", n.groupId)
      assertEquals(NotificationType.ERROR, n.type)
      assertTrue(n.content, n.content.contains(AgentKind.CLAUDE.displayName) && n.content.contains(account.dir))
      assertEquals(1, logins.size)
    } finally {
      connection.disconnect()
      base.toFile().deleteRecursively()
    }
  }
}
