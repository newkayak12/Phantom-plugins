// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import java.util.MissingResourceException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Suggests Phantom's Ghost Mode once per IDE run when an agent session starts and the mode is off. Phantom owns the
 * `phantom.ghost` registry key; an IDE without it never sees the suggestion.
 */
object GhostModeSuggestion {
  const val KEY = "phantom.ghost"
  private val offered = AtomicBoolean(false)

  /** Phantom's key, or null when this IDE does not register it. */
  private fun ghostKey(): RegistryValue? = try {
    Registry.get(KEY).also { it.asBoolean() }
  } catch (_: MissingResourceException) {
    null
  }

  internal fun shouldOffer(): Boolean =
    !offered.get() && !AiState.getInstance().state.ghostModeDeclined && ghostKey()?.asBoolean() == false

  fun offer(project: Project) {
    if (!shouldOffer() || !offered.compareAndSet(false, true)) return
    bestEffort {
      NotificationGroupManager.getInstance().getNotificationGroup(AiNotify.GROUP)
        .createNotification(PhantomAiBundle.message("ghost.suggest.text"), NotificationType.INFORMATION)
        .addAction(NotificationAction.createSimpleExpiring(PhantomAiBundle.message("ghost.suggest.turn.on")) { turnOn() })
        .addAction(NotificationAction.createSimpleExpiring(PhantomAiBundle.message("ghost.suggest.never")) { decline() })
        .notify(project)
    }
  }

  internal fun turnOn() {
    ghostKey()?.setValue(true)
  }

  internal fun decline() {
    AiState.getInstance().state.ghostModeDeclined = true
  }

  /** Lets the next session start offer it again, for tests. */
  internal fun resetForTests() {
    offered.set(false)
  }
}
