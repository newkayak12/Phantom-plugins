// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbExecutor
import dev.phantom.db.core.exec.DbMetadataCache
import java.sql.SQLException
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Data source editor. Name, URL, user and password are all most people touch; the driver, row limit and timeout sit
 * in a collapsed group and work without changes.
 */
class DbDataSourceDialog private constructor(private val project: Project, existing: DbDataSource?) : DialogWrapper(project) {
  private val isNew = existing == null
  private val source = existing?.copy() ?: DbDataSource().apply { url = Dbms.MYSQL.urlTemplate }

  private val nameField = JBTextField(source.name)
  private val dbmsCombo = ComboBox(Dbms.entries.toTypedArray()).apply {
    selectedItem = source.dbms
    renderer = SimpleListCellRenderer.create { label, value, _ ->
      label.text = value?.displayName.orEmpty()
      label.icon = value?.icon
    }
  }
  private val urlField = JBTextField(source.url)
  private val userField = JBTextField(source.user)
  private val passwordField = JBPasswordField()
  private val readOnlyBox = JBCheckBox(PhantomDbBundle.message("db.dialog.read.only"), source.readOnly)
  private val driverLabel = JBLabel(" ")
  private val maxRowsField = JBIntSpinner(source.maxRows, 1, 10_000)
  private val timeoutField = JBIntSpinner(source.queryTimeoutSeconds, 0, 3_600)
  private val testResult = JBLabel(" ")
  private var driverJars: List<String> = source.driverJars.toList()
  private var passwordEdited = false

  init {
    title = PhantomDbBundle.message(if (isNew) "db.dialog.title.new" else "db.dialog.title.edit")
    if (!isNew) passwordField.emptyText.text = PhantomDbBundle.message("db.dialog.password.saved")
    passwordField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        passwordEdited = true
      }
    })
    dbmsCombo.addActionListener {
      val dbms = dbmsCombo.selectedItem as Dbms
      val url = urlField.text.trim()
      if (url.isEmpty() || Dbms.entries.any { it.urlTemplate == url } || Dbms.forUrl(url) != dbms) urlField.text = dbms.urlTemplate
      refreshDriver()
    }
    urlField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        val detected = Dbms.forUrl(urlField.text) ?: return
        if (detected != dbmsCombo.selectedItem) {
          dbmsCombo.selectedItem = detected
        }
      }
    })
    init()
    refreshDriver()
  }

  override fun getPreferredFocusedComponent(): JComponent = if (isNew) urlField else passwordField

  override fun createCenterPanel(): JComponent = panel {
    row(PhantomDbBundle.message("db.dialog.name")) { cell(nameField).align(AlignX.FILL).comment(PhantomDbBundle.message("db.dialog.name.hint")) }
    row(PhantomDbBundle.message("db.dialog.dbms")) { cell(dbmsCombo) }
    row(PhantomDbBundle.message("db.dialog.url")) { cell(urlField).align(AlignX.FILL).columns(40) }
    row(PhantomDbBundle.message("db.dialog.user")) { cell(userField).align(AlignX.FILL) }
    row(PhantomDbBundle.message("db.dialog.password")) { cell(passwordField).align(AlignX.FILL) }
    row { cell(readOnlyBox) }
    collapsibleGroup(PhantomDbBundle.message("db.dialog.options")) {
      row(PhantomDbBundle.message("db.dialog.driver")) {
        cell(driverLabel)
        link(PhantomDbBundle.message("db.dialog.driver.choose")) { chooseJars() }
        link(PhantomDbBundle.message("db.dialog.driver.auto")) {
          driverJars = emptyList()
          refreshDriver()
        }
      }
      row(PhantomDbBundle.message("db.dialog.max.rows")) { cell(maxRowsField) }
      row(PhantomDbBundle.message("db.dialog.timeout")) { cell(timeoutField) }
    }
    row {
      button(PhantomDbBundle.message("db.dialog.test")) { testConnection() }
      cell(testResult).align(AlignX.FILL)
    }
  }

  override fun doValidate(): ValidationInfo? {
    val url = urlField.text.trim()
    if (!url.startsWith("jdbc:", ignoreCase = true)) return ValidationInfo(PhantomDbBundle.message("db.dialog.url.invalid"), urlField)
    return null
  }

  private fun current(): DbDataSource = source.copy().apply {
    dbms = dbmsCombo.selectedItem as Dbms
    url = urlField.text.trim()
    user = userField.text.trim()
    name = nameField.text.trim().ifEmpty { defaultName(dbms, url) }
    readOnly = readOnlyBox.isSelected
    driverJars = this@DbDataSourceDialog.driverJars.toMutableList()
    maxRows = maxRowsField.number
    queryTimeoutSeconds = timeoutField.number
  }

  private fun refreshDriver() {
    val probe = current()
    driverLabel.text = PhantomDbBundle.message("db.dialog.driver.searching")
    ApplicationManager.getApplication().executeOnPooledThread {
      val found = DbDrivers.find(project, probe)
      ApplicationManager.getApplication().invokeLater({
        driverLabel.text = found?.description ?: PhantomDbBundle.message("db.dialog.driver.none", probe.dbms.artifacts.first())
        driverLabel.foreground = if (found == null) JBColor.RED else UIUtil.getLabelForeground()
      }, ModalityState.any())
    }
  }

  private fun chooseJars() {
    val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withExtensionFilter("jar")
    val files = FileChooser.chooseFiles(descriptor, project, null)
    if (files.isEmpty()) return
    driverJars = files.map { it.path }
    refreshDriver()
  }

  private fun testConnection() {
    val probe = current()
    val typed = if (passwordEdited || isNew) String(passwordField.password) else null
    testResult.text = " "
    var result: String? = null
    var error: String? = null
    ProgressManager.getInstance().run(object : Task.Modal(project, PhantomDbBundle.message("db.dialog.testing"), true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val password = typed ?: DbDataSourceService.getInstance(project).password(probe.id)
          result = DbConnectionService.getInstance(project).testConnection(probe, password)
        }
        catch (e: SQLException) {
          error = DbExecutor.message(e)
        }
        catch (e: Exception) {
          error = e.message ?: e.javaClass.simpleName
        }
      }
    })
    testResult.text = result ?: error.orEmpty()
    testResult.foreground = if (result != null) JBColor(0x2E7D32, 0x6AAB73) else JBColor.RED
  }

  override fun doOKAction() {
    val saved = current()
    val password = if (passwordEdited || isNew) passwordField.password else null
    DbDataSourceService.getInstance(project).save(saved, password)
    DbConnectionService.getInstance(project).disconnect(saved.id)
    DbMetadataCache.getInstanceIfCreated(project)?.invalidate(saved.id)
    result = saved
    super.doOKAction()
  }

  private var result: DbDataSource? = null

  companion object {
    /** Shows the dialog for a new data source; returns it once saved. */
    fun create(project: Project): DbDataSource? = DbDataSourceDialog(project, null).let { if (it.showAndGet()) it.result else null }

    fun edit(project: Project, dataSource: DbDataSource): DbDataSource? =
      DbDataSourceDialog(project, dataSource).let { if (it.showAndGet()) it.result else null }

    /** `mysql@localhost`, `h2:mem:test`. */
    fun defaultName(dbms: Dbms, url: String): String {
      val rest = url.substringAfter(dbms.urlPrefix, url)
      val host = rest.removePrefix("//").substringBefore('/').substringBefore('?').substringBefore(';')
      return if (rest.startsWith("//") && host.isNotEmpty()) "${dbms.displayName.lowercase()}@${host.substringBefore(':')}"
      else "${dbms.displayName.lowercase()}:${rest.substringBefore(';')}"
    }
  }
}
