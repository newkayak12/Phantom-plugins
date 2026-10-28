// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** Logs a failure and tells the user; the balloon is best-effort (no application in plain unit tests). */
object AiNotify {
  const val GROUP = "Phantom AI Integration"

  fun error(log: Logger, project: Project?, message: String, cause: Throwable?) {
    log.warn(message, cause)
    bestEffort {
      NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        .createNotification(PhantomAiBundle.message("notify.title"), message, NotificationType.ERROR)
        .notify(project)
    }
  }
}
