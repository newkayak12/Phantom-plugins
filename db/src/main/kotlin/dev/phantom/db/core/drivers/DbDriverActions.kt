// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.drivers

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.ui.DbDataSourceDialog

/** Balloon actions for a connection that has no driver: nothing is downloaded until the user clicks. */
object DbDriverActions {
  /** Task 7 retargets this to the Drivers dialog, selecting `dataSource.driverId`. */
  fun configureDrivers(project: Project, dataSource: DbDataSource) {
    invokeLater(ModalityState.nonModal()) { DbDataSourceDialog.edit(project, dataSource) }
  }

  fun notifyMissing(project: Project, dataSource: DbDataSource, definition: DbDriverDefinition?, message: String) {
    val notification = NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.driver.notification.title"), message, NotificationType.WARNING)
    if (definition != null && definition.artifacts.isNotEmpty()) {
      notification.addAction(NotificationAction.createSimpleExpiring(PhantomDbBundle.message("db.driver.action.download")) { download(project, definition) })
    }
    notification.addAction(NotificationAction.createSimpleExpiring(PhantomDbBundle.message("db.driver.action.configure")) { configureDrivers(project, dataSource) })
    notification.notify(project)
  }

  fun download(project: Project, definition: DbDriverDefinition) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.driver.download.title", definition.name), true) {
      override fun run(indicator: ProgressIndicator) {
        val downloaded = try {
          DbDriverDownloader.download(definition, indicator)
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          notify(project, PhantomDbBundle.message("db.driver.download.failed", definition.name, e.message ?: e.javaClass.simpleName), NotificationType.ERROR)
          return
        }
        DbDriverRegistry.getInstance().save(downloaded)
        notify(project, PhantomDbBundle.message("db.driver.download.done", definition.name), NotificationType.INFORMATION)
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.driver.notification.title"), message, type).notify(project)
  }
}
