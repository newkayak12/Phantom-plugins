// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.DbPasswordStorage
import dev.phantom.db.core.PhantomDbBundle
import javax.swing.JComponent

/** Asks for the user and password at connect time, as DataGrip does, with where to keep the password. */
object DbPasswordPrompt {
  class SignIn(val user: String, val password: String)

  /**
   * Blocking; from a background thread without a read lock, else (and headless) `null`. [error] is the database's refusal
   * of the last try. A password kept "forever" or "until restart" is saved with the data source; `null`: cancelled.
   */
  fun ask(project: Project, dataSource: DbDataSource, error: String?): SignIn? {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread || app.isReadAccessAllowed || app.isHeadlessEnvironment) return null
    var result: SignIn? = null
    app.invokeAndWait({
      if (project.isDisposed) return@invokeAndWait
      val dialog = Dialog(project, dataSource, error)
      if (!dialog.showAndGet()) return@invokeAndWait
      val signIn = SignIn(dialog.user.text.trim(), String(dialog.password.password))
      val storage = dialog.storage.selectedItem as DbPasswordStorage
      val service = DbDataSourceService.getInstance(project)
      service.find(dataSource.id)?.copy()?.let { saved ->
        saved.user = signIn.user
        saved.credentialStorage = storage
        service.save(saved, signIn.password.toCharArray())
      }
      result = signIn
    }, ModalityState.any())
    return result
  }

  fun storageName(storage: DbPasswordStorage): String = PhantomDbBundle.message("db.password.save.${storage.name.lowercase()}")

  private class Dialog(project: Project, private val dataSource: DbDataSource, private val error: String?) : DialogWrapper(project) {
    val user = JBTextField(dataSource.user)
    val password = JBPasswordField()
    val storage = ComboBox(DbPasswordStorage.entries.toTypedArray()).apply {
      selectedItem = dataSource.credentialStorage
      renderer = SimpleListCellRenderer.create("") { storageName(it) }
    }

    init {
      title = PhantomDbBundle.message("db.password.title", dataSource.name)
      init()
    }

    override fun getPreferredFocusedComponent(): JComponent = if (user.text.isEmpty()) user else password

    override fun createCenterPanel(): JComponent = panel {
      if (error != null) row { label(error).applyToComponent { foreground = com.intellij.ui.JBColor.RED } }
      row(PhantomDbBundle.message("db.dialog.user")) { cell(user).align(AlignX.FILL).columns(COLUMNS_MEDIUM) }
      row(PhantomDbBundle.message("db.dialog.password")) { cell(password).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.password.save")) { cell(storage) }
    }
  }
}
