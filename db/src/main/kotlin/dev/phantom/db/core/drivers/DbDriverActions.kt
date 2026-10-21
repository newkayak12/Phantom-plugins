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
import dev.phantom.db.core.driver
import dev.phantom.db.core.ui.DbDriversDialog

/** Balloon actions for a connection that has no driver: nothing is downloaded until the user clicks. */
object DbDriverActions {
  /** The live balloon per (data source, driver): a console retry must not stack a second one. */
  private val live = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, com.intellij.notification.Notification>()

  fun configureDrivers(project: Project, dataSource: DbDataSource) {
    invokeLater(ModalityState.nonModal()) { DbDriversDialog(project, select = dataSource.driverId ?: dataSource.driver()?.id).show() }
  }

  fun notifyMissing(project: Project, dataSource: DbDataSource, definition: DbDriverDefinition?, message: String) {
    val key = dataSource.id to (dataSource.driverId ?: "")
    if (live[key]?.isExpired == false) return
    val notification = NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.driver.notification.title"), message, NotificationType.WARNING)
    if (definition != null && definition.artifacts.isNotEmpty()) {
      notification.addAction(NotificationAction.createSimpleExpiring(PhantomDbBundle.message("db.driver.action.download")) { download(project, definition) })
    }
    notification.addAction(NotificationAction.createSimpleExpiring(PhantomDbBundle.message("db.driver.action.configure")) { configureDrivers(project, dataSource) })
    live[key] = notification
    notification.notify(project)
  }

  fun download(project: Project?, definition: DbDriverDefinition) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.driver.download.title", definition.name), true) {
      override fun run(indicator: ProgressIndicator) {
        val pinned = try {
          downloadAndPin(definition, indicator)
        }
        catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          notify(project, PhantomDbBundle.message("db.driver.download.failed", definition.name, e.message ?: e.javaClass.simpleName), NotificationType.ERROR)
          return
        }
        if (!pinned) return  // the driver was removed meanwhile
        live.entries.removeIf { (key, n) -> (key.second == definition.id).also { if (it) n.expire() } }
        notify(project, PhantomDbBundle.message("db.driver.download.done", definition.name), NotificationType.INFORMATION)
      }
    })
  }

  /**
   * Downloads [definition]'s jars and pins them to the driver with its id as it is when the download ends: nothing else
   * of [definition] is saved, so edits made meanwhile stay and a driver removed meanwhile is not brought back (false).
   */
  internal fun downloadAndPin(
    definition: DbDriverDefinition,
    indicator: ProgressIndicator,
    download: (DbDriverDefinition, ProgressIndicator) -> DbDriverDefinition = { d, i -> DbDriverDownloader.download(d, i) },
  ): Boolean {
    val downloaded = download(definition, indicator)
    return DbDriverRegistry.getInstance().pin(definition.id, downloaded.resolvedVersion, downloaded.resolvedJars)
  }

  private fun notify(project: Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.driver.notification.title"), message, type).notify(project)
  }
}
