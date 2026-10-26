// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProcessCanceledException
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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.drivers.DbDriverActions
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverDownloader
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbTransactions
import dev.phantom.db.core.exec.DbExecutor
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbSsl
import java.awt.BorderLayout
import javax.swing.JPanel
import java.sql.SQLException
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Data source editor. Name, driver, URL, user and password are all most people touch; driver files, limits and
 * connection properties sit in collapsed groups and work without changes.
 */
class DbDataSourceDialog internal constructor(private val project: Project, existing: DbDataSource?) : DialogWrapper(project) {
  private val registry = DbDriverRegistry.getInstance()
  private val isNew = existing == null
  private val source = existing?.copy() ?: DbDataSource().apply {
    driverId = DEFAULT_DRIVER
    url = registry.find(DEFAULT_DRIVER)?.defaultUrl().orEmpty()
  }

  private val nameField = JBTextField(source.name)
  internal val colorCombo = ComboBox(DbColors.keys.toTypedArray()).apply {
    selectedItem = source.color.takeIf { it in DbColors.keys } ?: ""
    renderer = listCellRenderer<String> {
      text(DbColors.displayName(value))
      icon(DbColors.swatch(value))
    }
  }
  internal val driverCombo = ComboBox<DbDriverDefinition>().apply { renderer = driverRenderer { driverItems().firstOrNull { !it.builtIn } } }
  internal val urlField = JBTextField(source.url)
  private val userField = JBTextField(source.user)
  private val passwordField = JBPasswordField()
  internal val credentialStorageCombo = ComboBox(DbPasswordStorage.entries.toTypedArray()).apply {
    selectedItem = source.credentialStorage
    renderer = com.intellij.ui.SimpleListCellRenderer.create("") { DbPasswordPrompt.storageName(it) }
  }
  internal val authCombo = ComboBox(DbAuthKind.entries.toTypedArray()).apply {
    selectedItem = source.authKind
    renderer = textListCellRenderer { it?.let { kind -> PhantomDbBundle.message("db.dialog.auth.${kind.name.lowercase()}") } }
  }
  internal val hostField = JBTextField("localhost")
  internal val portField = JBTextField()
  internal val databaseField = JBTextField()
  /** Off: the URL is built from the host, port and database fields. A data source opened for editing starts on: fields are not parsed back from a URL. */
  internal val urlOnlyBox = JBCheckBox(PhantomDbBundle.message("db.dialog.url.only"), !isNew)
  private val awsProfileField = JBTextField(source.awsProfile)
  private val awsRegionField = JBTextField(source.awsRegion)
  private val properties = DbPropertiesTable().apply { values = source.properties }
  private val readOnlyBox = JBCheckBox(PhantomDbBundle.message("db.dialog.read.only"), source.readOnly)
  private val manualCommitBox = JBCheckBox(PhantomDbBundle.message("db.dialog.manual.commit"), source.manualCommit)
  private val driverLabel = JBLabel(" ")
  private val downloadLink = ActionLink(PhantomDbBundle.message("db.dialog.driver.download")) { downloadDriver() }.apply { isVisible = false }
  private val maxRowsField = JBIntSpinner(source.maxRows, 1, 10_000)
  private val timeoutField = JBIntSpinner(source.queryTimeoutSeconds, 0, 3_600)
  private val testResult = JBLabel(" ")
  internal val sshBox = JBCheckBox(PhantomDbBundle.message("db.dialog.ssh.use"), source.sshEnabled)
  internal val sshHostField = JBTextField(source.sshHost)
  internal val sshPortField = JBIntSpinner(source.sshPort.takeIf { it in 1..65535 } ?: 22, 1, 65535)
  internal val sshUserField = JBTextField(source.sshUser)
  internal val sshAuthCombo = ComboBox(DbSshAuth.entries.toTypedArray()).apply {
    selectedItem = source.sshAuth
    renderer = textListCellRenderer { it?.let { kind -> PhantomDbBundle.message("db.dialog.ssh.auth.${kind.name.lowercase()}") } }
  }
  private val sshPasswordField = JBPasswordField()
  internal val sshKeyField = TextFieldWithBrowseButton().apply {
    text = source.sshKeyFile
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withShowHiddenFiles(true))
  }
  private val sshPassphraseField = JBPasswordField()
  internal val sshRemoteHostField = JBTextField(source.sshRemoteHost)
  internal val sshRemotePortField = JBTextField(source.sshRemotePort?.toString().orEmpty())
  internal val sslBox = JBCheckBox(PhantomDbBundle.message("db.dialog.ssl.use"), source.sslEnabled)
  internal val sslModeCombo = ComboBox(DbSslMode.entries.toTypedArray()).apply {
    selectedItem = source.sslMode
    renderer = textListCellRenderer { it?.let { mode -> PhantomDbBundle.message("db.dialog.ssl.mode.${mode.name.lowercase()}") } }
  }
  private val sslCaField = fileField(source.sslCaFile)
  private val sslCertField = fileField(source.sslCertFile)
  private val sslKeyField = fileField(source.sslKeyFile)
  internal val sslHint = JBLabel(PhantomDbBundle.message("db.dialog.ssl.generic")).apply { foreground = UIUtil.getContextHelpForeground() }
  private lateinit var sshPasswordRow: Row
  private lateinit var sshKeyRow: Row
  private lateinit var sshPassphraseRow: Row
  private lateinit var sshAgentRow: Row
  /** Typed in this dialog, per field: the SSH password and the key passphrase are saved apart. */
  private val sshSecretEdited = HashSet<JBPasswordField>()
  private val sslCaLabel = JBLabel(PhantomDbBundle.message("db.dialog.ssl.ca"))
  private val sslCertLabel = JBLabel(PhantomDbBundle.message("db.dialog.ssl.cert"))
  private lateinit var passwordRow: Row
  private lateinit var awsProfileRow: Row
  private lateinit var awsRegionRow: Row
  private lateinit var hostRow: Row
  private lateinit var portRow: Row
  private lateinit var databaseRow: Row
  private var driverJars: List<String> = source.driverJars.toList()
  private var passwordEdited = false
  /** The driver whose template the URL may still hold: switching away from it replaces only that (or an empty URL). */
  private var shownDriver: DbDriverDefinition? = null
  /** Set while the URL picks the driver, so the switch does not touch the URL in turn. */
  private var selectingFromUrl = false
  /** Set while the URL is built from the fields; any other change to it is a typed URL. */
  private var generatingUrl = false
  /** URL only was turned on because the driver's template has placeholders the fields cannot fill, not by the user. */
  private var urlOnlyForTemplate = false

  init {
    title = PhantomDbBundle.message(if (isNew) "db.dialog.title.new" else "db.dialog.title.edit")
    if (!isNew) passwordField.emptyText.text = PhantomDbBundle.message("db.dialog.password.saved")
    passwordField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        passwordEdited = true
      }
    })
    for (field in listOf(sshPasswordField, sshPassphraseField)) {
      if (!isNew) field.emptyText.text = PhantomDbBundle.message("db.dialog.password.saved")
      field.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          sshSecretEdited += field
        }
      })
    }
    sshAuthCombo.addActionListener { updateSshRows() }
    sshBox.addActionListener { updateSshRows() }
    sslBox.addActionListener { updateSslRows() }
    // an id this IDE does not know stays unselected (and saved as is) until the user picks a driver
    val initial = registry.find(source.driverId) ?: if (source.driverId.isNullOrEmpty()) source.driver() else null
    fillDrivers(initial?.id)
    shownDriver = selectedDriver()
    portField.text = shownDriver?.defaultPort?.toString().orEmpty()
    driverCombo.addActionListener { driverChosen() }
    urlField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        if (generatingUrl) return
        urlOnlyForTemplate = false
        if (!urlOnlyBox.isSelected) {
          urlOnlyBox.isSelected = true
          updateUrlMode()
        }
        val detected = registry.forUrl(urlField.text) ?: return
        if (detected.id == selectedDriver()?.id) return
        selectingFromUrl = true
        try {
          driverCombo.selectedItem = driverItem(detected.id)
        }
        finally {
          selectingFromUrl = false
        }
      }
    })
    for (field in listOf(hostField, portField, databaseField)) {
      field.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          if (!generatingUrl && !urlOnlyBox.isSelected) generateUrl()
        }
      })
    }
    urlOnlyBox.addActionListener {
      urlOnlyForTemplate = false
      if (!urlOnlyBox.isSelected) generateUrl()
      updateUrlMode()
    }
    updateAuthKinds()
    authCombo.addActionListener { updateAuthRows() }
    registry.addListener(disposable) {
      ApplicationManager.getApplication().invokeLater({ if (!isDisposed) driversChanged() }, ModalityState.any())
    }
    init()
    updateAuthRows()
    updateUrlMode()
    updateSshRows()
    updateSslRows()
    refreshDriver()
  }

  override fun getPreferredFocusedComponent(): JComponent = if (isNew) urlField else passwordField

  override fun createCenterPanel(): JComponent {
    val tabs = JBTabbedPane()
    tabs.addTab(PhantomDbBundle.message("db.dialog.tab.general"), generalPanel())
    tabs.addTab(PhantomDbBundle.message("db.dialog.tab.ssh"), sshSslPanel())
    val bottom = panel {
      row {
        button(PhantomDbBundle.message("db.dialog.test")) { testConnection() }
        cell(testResult).align(AlignX.FILL)
      }
    }
    return JPanel(BorderLayout()).apply {
      add(tabs, BorderLayout.CENTER)
      add(bottom, BorderLayout.SOUTH)
    }
  }

  private fun sshSslPanel(): JComponent = panel {
    group(PhantomDbBundle.message("db.dialog.ssh")) {
      row { cell(sshBox) }
      row(PhantomDbBundle.message("db.dialog.ssh.host")) {
        cell(sshHostField).align(AlignX.FILL)
        label(PhantomDbBundle.message("db.dialog.port"))
        cell(sshPortField)
      }
      row(PhantomDbBundle.message("db.dialog.user")) { cell(sshUserField).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.dialog.auth")) { cell(sshAuthCombo) }
      sshPasswordRow = row(PhantomDbBundle.message("db.dialog.password")) { cell(sshPasswordField).align(AlignX.FILL) }
      sshKeyRow = row(PhantomDbBundle.message("db.dialog.ssh.key")) { cell(sshKeyField).align(AlignX.FILL) }
      sshPassphraseRow = row(PhantomDbBundle.message("db.dialog.ssh.passphrase")) { cell(sshPassphraseField).align(AlignX.FILL) }
      sshAgentRow = row("") { comment(PhantomDbBundle.message("db.dialog.ssh.agent.hint")) }
      row(PhantomDbBundle.message("db.dialog.ssh.remote")) {
        cell(sshRemoteHostField).align(AlignX.FILL)
        label(PhantomDbBundle.message("db.dialog.port"))
        cell(sshRemotePortField).columns(6)
      }.rowComment(PhantomDbBundle.message("db.dialog.ssh.remote.hint"))
    }
    group(PhantomDbBundle.message("db.dialog.ssl")) {
      row { cell(sslBox) }
      row { cell(sslHint) }
      row(PhantomDbBundle.message("db.dialog.ssl.mode")) { cell(sslModeCombo) }
      row(sslCaLabel) { cell(sslCaField).align(AlignX.FILL) }
      row(sslCertLabel) { cell(sslCertField).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.dialog.ssl.key")) { cell(sslKeyField).align(AlignX.FILL) }
    }
  }

  private fun fileField(path: String) = TextFieldWithBrowseButton().apply {
    text = path
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor())
  }

  private fun updateSshRows() {
    if (!::sshPasswordRow.isInitialized) return
    val on = sshBox.isSelected
    val auth = sshAuthCombo.selectedItem as DbSshAuth
    for (c in listOf(sshHostField, sshPortField, sshUserField, sshAuthCombo, sshPasswordField, sshKeyField, sshPassphraseField, sshRemoteHostField, sshRemotePortField)) {
      c.isEnabled = on
    }
    sshPasswordRow.visible(auth == DbSshAuth.PASSWORD)
    sshKeyRow.visible(auth == DbSshAuth.KEY)
    sshPassphraseRow.visible(auth == DbSshAuth.KEY)
    sshAgentRow.visible(auth == DbSshAuth.AGENT)
  }

  /** The driver's SSL mapping decides what can be set; a driver without one points at the Properties table. */
  private fun updateSslRows() {
    val driver = selectedDriver()
    val supported = DbSsl.supports(driver)
    sslHint.isVisible = !supported
    sslBox.isEnabled = supported
    val on = supported && sslBox.isSelected
    sslModeCombo.isEnabled = on
    sslCaField.isEnabled = on && DbSsl.takesFile(driver, DbSsl.CA)
    sslCertField.isEnabled = on && DbSsl.takesFile(driver, DbSsl.CERT)
    sslKeyField.isEnabled = on && DbSsl.takesFile(driver, DbSsl.KEY)
    // MySQL Connector/J (and MariaDB's client certificate) take Java keystores, not PEM files
    sslCaLabel.text = PhantomDbBundle.message(if (DbSsl.isKeystore(driver, DbSsl.CA)) "db.dialog.ssl.ca.keystore" else "db.dialog.ssl.ca")
    sslCertLabel.text = PhantomDbBundle.message(if (DbSsl.isKeystore(driver, DbSsl.CERT)) "db.dialog.ssl.cert.keystore" else "db.dialog.ssl.cert")
  }

  /** The SSH password or key passphrase typed in the dialog; `null`: unchanged (the saved one). */
  private fun typedSshSecret(): CharArray? {
    val field = when (sshAuthCombo.selectedItem as DbSshAuth) {
      DbSshAuth.PASSWORD -> sshPasswordField
      DbSshAuth.KEY -> sshPassphraseField
      DbSshAuth.AGENT -> return null
    }
    return if (isNew || field in sshSecretEdited) field.password else null
  }

  private fun generalPanel(): JComponent = panel {
    row(PhantomDbBundle.message("db.dialog.name")) { cell(nameField).align(AlignX.FILL).comment(PhantomDbBundle.message("db.dialog.name.hint")) }
    row(PhantomDbBundle.message("db.dialog.color")) { cell(colorCombo) }
    row(PhantomDbBundle.message("db.dialog.driver")) {
      cell(driverCombo)
      link(PhantomDbBundle.message("db.dialog.drivers")) { openDrivers() }
    }
    row("") {
      cell(driverLabel)
      cell(downloadLink)
    }
    hostRow = row(PhantomDbBundle.message("db.dialog.host")) { cell(hostField).align(AlignX.FILL) }
    portRow = row(PhantomDbBundle.message("db.dialog.port")) { cell(portField).columns(6) }
    databaseRow = row(PhantomDbBundle.message("db.dialog.database")) { cell(databaseField).align(AlignX.FILL) }
    row(PhantomDbBundle.message("db.dialog.url")) {
      cell(urlField).align(AlignX.FILL).columns(40)
      cell(urlOnlyBox)
    }
    row(PhantomDbBundle.message("db.dialog.auth")) { cell(authCombo) }
    row(PhantomDbBundle.message("db.dialog.user")) { cell(userField).align(AlignX.FILL) }
    passwordRow = row(PhantomDbBundle.message("db.dialog.password")) {
      cell(passwordField).align(AlignX.FILL)
      label(PhantomDbBundle.message("db.password.save"))
      cell(credentialStorageCombo)
    }
    awsProfileRow = row(PhantomDbBundle.message("db.dialog.aws.profile")) { cell(awsProfileField).align(AlignX.FILL).comment(PhantomDbBundle.message("db.dialog.aws.profile.hint")) }
    awsRegionRow = row(PhantomDbBundle.message("db.dialog.aws.region")) { cell(awsRegionField).align(AlignX.FILL) }
    row { cell(readOnlyBox) }
    row { cell(manualCommitBox).comment(PhantomDbBundle.message("db.dialog.manual.commit.hint")) }
    collapsibleGroup(PhantomDbBundle.message("db.dialog.options")) {
      row(PhantomDbBundle.message("db.dialog.driver.files")) {
        link(PhantomDbBundle.message("db.dialog.driver.choose")) { chooseJars() }
        link(PhantomDbBundle.message("db.dialog.driver.auto")) {
          driverJars = emptyList()
          refreshDriver()
        }
      }
      row(PhantomDbBundle.message("db.dialog.max.rows")) { cell(maxRowsField) }
      row(PhantomDbBundle.message("db.dialog.timeout")) { cell(timeoutField) }
    }
    collapsibleGroup(PhantomDbBundle.message("db.dialog.properties")) {
      row { cell(properties.component).align(AlignX.FILL).comment(PhantomDbBundle.message("db.dialog.properties.hint")) }
    }
  }

  public override fun doValidate(): ValidationInfo? {
    // only an unchanged id this IDE does not know (shared by a teammate) may be saved without a selection
    if (selectedDriver() == null && (source.driverId.isNullOrEmpty() || registry.find(source.driverId) != null)) {
      return ValidationInfo(PhantomDbBundle.message("db.dialog.driver.required"), driverCombo)
    }
    val url = urlField.text.trim()
    if (!url.startsWith("jdbc:", ignoreCase = true)) return ValidationInfo(PhantomDbBundle.message("db.dialog.url.invalid"), urlField)
    if (sshBox.isSelected) {
      if (sshHostField.text.isBlank()) return ValidationInfo(PhantomDbBundle.message("db.ssh.host.required"), sshHostField)
      val port = sshRemotePortField.text.trim()
      if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) {
        return ValidationInfo(PhantomDbBundle.message("db.dialog.ssh.remote.port.invalid"), sshRemotePortField)
      }
      if (sshAuthCombo.selectedItem == DbSshAuth.KEY && sshKeyField.text.isBlank()) return ValidationInfo(PhantomDbBundle.message("db.ssh.key.required"), sshKeyField.textField)
    }
    // through a tunnel the URL names 127.0.0.1, which a certificate checked against the host name never matches
    if (DbSsl.verifyFullBreaksWithTunnel(selectedDriver(), current())) {
      return ValidationInfo(PhantomDbBundle.message("db.dialog.ssl.verify.full.tunnel"), sslModeCombo).asWarning().withOKEnabled()
    }
    return null
  }

  internal fun current(): DbDataSource = source.copy().apply {
    val driver = selectedDriver()
    driverId = driver?.id ?: source.driverId
    dbms = driver?.dialect ?: source.dbms
    url = urlField.text.trim()
    user = userField.text.trim()
    name = nameField.text.trim().ifEmpty { defaultName(driver, url) }
    authKind = authCombo.selectedItem as DbAuthKind
    awsProfile = awsProfileField.text.trim()
    awsRegion = awsRegionField.text.trim()
    properties = LinkedHashMap(this@DbDataSourceDialog.properties.values)
    readOnly = readOnlyBox.isSelected
    manualCommit = manualCommitBox.isSelected
    credentialStorage = credentialStorageCombo.selectedItem as DbPasswordStorage
    color = colorCombo.selectedItem as String
    driverJars = this@DbDataSourceDialog.driverJars.toMutableList()
    maxRows = maxRowsField.number
    queryTimeoutSeconds = timeoutField.number
    sshEnabled = sshBox.isSelected
    sshHost = sshHostField.text.trim()
    sshPort = sshPortField.number
    sshUser = sshUserField.text.trim()
    sshAuth = sshAuthCombo.selectedItem as DbSshAuth
    sshKeyFile = sshKeyField.text.trim()
    sshRemoteHost = sshRemoteHostField.text.trim()
    sshRemotePort = sshRemotePortField.text.trim().toIntOrNull()
    sslEnabled = sslBox.isSelected
    sslMode = sslModeCombo.selectedItem as DbSslMode
    sslCaFile = sslCaField.text.trim()
    sslCertFile = sslCertField.text.trim()
    sslKeyFile = sslKeyField.text.trim()
  }

  private fun selectedDriver(): DbDriverDefinition? = driverCombo.selectedItem as? DbDriverDefinition

  private fun driverItems(): List<DbDriverDefinition> = (0 until driverCombo.itemCount).map { driverCombo.getItemAt(it) }

  private fun driverItem(id: String?): DbDriverDefinition? = driverItems().firstOrNull { it.id == id }

  /** Built-ins, then the user's drivers, fresh from the registry. */
  private fun fillDrivers(select: String?) {
    driverCombo.model = CollectionComboBoxModel(registry.all.toMutableList())
    driverCombo.selectedItem = driverItem(select)
  }

  private fun driverChosen() {
    val chosen = selectedDriver()
    val previous = shownDriver
    if (chosen?.id != previous?.id && chosen != null && !selectingFromUrl) {
      val untouched = replacesUrl(urlField.text.trim(), previous)
      // a port still at the previous driver's default follows the new driver; a typed one stays
      val port = portField.text.trim()
      if (port.isEmpty() || port == previous?.defaultPort?.toString()) setGenerated { portField.text = chosen.defaultPort?.toString().orEmpty() }
      when {
        !chosen.urlFromFields() -> {
          // e.g. Snowflake's {account}: no field for it, so the URL is edited directly, starting from the template
          val wasFields = !urlOnlyBox.isSelected
          if (wasFields) {
            urlOnlyForTemplate = true
            urlOnlyBox.isSelected = true
          }
          if (wasFields || untouched) setGenerated { urlField.text = chosen.defaultUrl() }
        }
        urlOnlyForTemplate && untouched -> {
          urlOnlyForTemplate = false
          urlOnlyBox.isSelected = false
          generateUrl()
        }
        !urlOnlyBox.isSelected -> generateUrl()
        untouched -> urlField.text = chosen.defaultUrl()
      }
    }
    shownDriver = chosen
    updateAuthKinds()
    updateUrlMode()
    updateSslRows()
    refreshDriver()
  }

  private fun generateUrl() {
    val driver = selectedDriver() ?: return
    setGenerated {
      urlField.text = fillTemplate(driver.urlTemplate, mapOf("host" to hostField.text.trim(), "port" to portField.text.trim(), "database" to databaseField.text.trim()))
    }
  }

  /** Changes made by the dialog itself, not typed: they neither switch to URL only nor rebuild the URL. */
  private fun setGenerated(change: () -> Unit) {
    val was = generatingUrl
    generatingUrl = true
    try {
      change()
    }
    finally {
      generatingUrl = was
    }
  }

  /** Shows the fields the driver's URL template has, unless the URL is edited directly. */
  private fun updateUrlMode() {
    if (!::hostRow.isInitialized) return
    val template = selectedDriver()?.urlTemplate.orEmpty()
    val fields = !urlOnlyBox.isSelected
    hostRow.visible(fields && "{host}" in template)
    portRow.visible(fields && "{port}" in template)
    databaseRow.visible(fields && "{database}" in template)
    urlField.isEditable = !fields || URL_FIELDS.none { "{$it}" in template }
  }

  /** AWS IAM is offered only by drivers that know how to sign in with it (or an unknown driver, whose choice is kept). */
  private fun updateAuthKinds() {
    val driver = selectedDriver()
    val iam = driver == null || driver.iamProperties.isNotEmpty()
    val kinds = DbAuthKind.entries.filter { it != DbAuthKind.AWS_IAM || iam }
    val current = authCombo.selectedItem as? DbAuthKind ?: source.authKind
    if (kinds.size == authCombo.itemCount && authCombo.selectedItem == current) return
    authCombo.model = CollectionComboBoxModel(kinds.toMutableList())
    authCombo.selectedItem = if (current in kinds) current else DbAuthKind.PASSWORD
  }

  /** The registry changed (a download finished, the Drivers dialog saved): reload the drivers, keep the choice. */
  private fun driversChanged() {
    // an unknown id may just have been added under Drivers
    val id = selectedDriver()?.id ?: source.driverId
    selectingFromUrl = true
    try {
      fillDrivers(id)
    }
    finally {
      selectingFromUrl = false
    }
    shownDriver = selectedDriver()
    refreshDriver()
  }

  private fun updateAuthRows() {
    if (!::passwordRow.isInitialized) return
    val kind = authCombo.selectedItem as DbAuthKind
    passwordRow.visible(kind == DbAuthKind.PASSWORD)
    val aws = showsAwsFields(kind, selectedDriver())
    awsProfileRow.visible(aws)
    awsRegionRow.visible(aws)
  }

  private fun refreshDriver() {
    val probe = current()
    val definition = selectedDriver()
    downloadLink.isVisible = false
    if (definition == null && driverJars.isEmpty()) {
      driverLabel.text = if (probe.driverId.isNullOrEmpty()) PhantomDbBundle.message("db.dialog.driver.pick")
                         else PhantomDbBundle.message("db.driver.unknown", probe.driverId.orEmpty())
      driverLabel.foreground = JBColor.RED
      return
    }
    driverLabel.text = PhantomDbBundle.message("db.dialog.driver.searching")
    driverLabel.foreground = UIUtil.getLabelForeground()
    ApplicationManager.getApplication().executeOnPooledThread {
      val found = DbDrivers.find(project, probe)
      ApplicationManager.getApplication().invokeLater({
        if (isDisposed || selectedDriver() !== definition) return@invokeLater
        driverLabel.text = found?.description ?: PhantomDbBundle.message("db.dialog.driver.none", definition?.name.orEmpty())
        driverLabel.foreground = if (found == null) JBColor.RED else UIUtil.getLabelForeground()
        downloadLink.isVisible = found == null && definition != null && definition.artifacts.isNotEmpty()
      }, ModalityState.any())
    }
  }

  private fun downloadDriver() {
    val definition = selectedDriver() ?: return
    downloadLink.isVisible = false
    driverLabel.text = PhantomDbBundle.message("db.drivers.status.downloading")
    DbDriverActions.download(project, definition)
  }

  private fun openDrivers() {
    DbDriversDialog(project, selectedDriver()?.id).show()
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
    val typedSsh = typedSshSecret()?.let { String(it) }
    testResult.text = " "
    var result: String? = null
    var error: String? = null
    ProgressManager.getInstance().run(object : Task.Modal(project, PhantomDbBundle.message("db.dialog.testing"), true) {
      override fun run(indicator: ProgressIndicator) {
        try {
          downloadIfMissing(project, probe, indicator)
          val password = typed ?: DbDataSourceService.getInstance(project).password(probe.id)
          val sshSecret = if (!probe.sshEnabled) null else typedSsh ?: DbDataSourceService.getInstance(project).sshSecret(probe)
          result = DbConnectionService.getInstance(project).testConnection(probe, password, sshSecret)
        }
        catch (e: ProcessCanceledException) {
          throw e
        }
        catch (e: SQLException) {
          error = DbExecutor.message(e)
        }
        catch (e: Exception) {
          error = e.message ?: e.javaClass.simpleName
        }
      }
    })
    refreshDriver()
    testResult.text = result ?: error.orEmpty()
    testResult.foreground = if (result != null) JBColor(0x2E7D32, 0x6AAB73) else JBColor.RED
  }

  override fun doOKAction() {
    // saving reconnects, which would roll back an open transaction unnoticed
    if (!isNew && !DbTransactions.endOrKeep(project, current(), title)) return
    val saved = current()
    val password = if (passwordEdited || isNew) passwordField.password else null
    DbDataSourceService.getInstance(project).save(saved, password, typedSshSecret())
    DbConnectionService.getInstance(project).disconnect(saved.id)
    if (saved.manualCommit && !saved.readOnly) DbChangesToolWindowFactory.ensureRegistered(project)
    DbMetadataCache.getInstanceIfCreated(project)?.invalidate(saved.id)
    result = saved
    super.doOKAction()
  }

  private var result: DbDataSource? = null

  companion object {
    private const val DEFAULT_DRIVER = "mysql"

    /**
     * Test Connection, an explicit user action, downloads a missing driver before trying: only the downloaded jars and
     * version are pinned in the registry, nothing else of the definition.
     */
    internal fun downloadIfMissing(
      project: Project,
      probe: DbDataSource,
      indicator: ProgressIndicator,
      download: (DbDriverDefinition, ProgressIndicator) -> DbDriverDefinition = { d, i -> DbDriverDownloader.download(d, i) },
    ) {
      if (probe.driverJars.isNotEmpty()) return
      val registry = DbDriverRegistry.getInstance()
      val definition = registry.find(probe.driverId) ?: return
      if (definition.artifacts.isEmpty() || DbDrivers.find(project, probe) != null) return
      indicator.text = PhantomDbBundle.message("db.driver.download.title", definition.name)
      DbDriverActions.downloadAndPin(definition, indicator, download)
      indicator.text = PhantomDbBundle.message("db.dialog.testing")
    }

    /** Switching drivers replaces an empty URL or the previous driver's untouched template; never a typed URL. */
    internal fun replacesUrl(url: String, previous: DbDriverDefinition?): Boolean =
      url.isEmpty() || (previous != null && url == previous.defaultUrl())

    /** AWS profile and region matter only for IAM sign-in through a driver that knows how to use them. */
    internal fun showsAwsFields(kind: DbAuthKind, driver: DbDriverDefinition?): Boolean =
      kind == DbAuthKind.AWS_IAM && driver?.iamProperties?.isNotEmpty() == true

    /** Shows the dialog for a new data source; returns it once saved. */
    fun create(project: Project): DbDataSource? = DbDataSourceDialog(project, null).let { if (it.showAndGet()) it.result else null }

    fun edit(project: Project, dataSource: DbDataSource): DbDataSource? =
      DbDataSourceDialog(project, dataSource).let { if (it.showAndGet()) it.result else null }

    /** `mysql@localhost`, `amazon redshift@cluster`, `h2:mem:test`: the driver's name, then the URL's host or what follows its prefix. */
    fun defaultName(driver: DbDriverDefinition?, url: String): String {
      val label = driver?.name?.lowercase() ?: "jdbc"
      val prefix = driver?.urlPrefixes?.filter { url.startsWith(it, ignoreCase = true) }?.maxByOrNull { it.length } ?: "jdbc:"
      val rest = if (url.startsWith(prefix, ignoreCase = true)) url.substring(prefix.length) else url
      val slashes = rest.indexOf("//")
      val host = if (slashes < 0) "" else rest.substring(slashes + 2).substringBefore('/').substringBefore('?').substringBefore(';').substringBefore(':')
      return if (host.isNotEmpty()) "$label@$host" else "$label:${rest.substringBefore(';')}"
    }
  }
}

/** Driver names with their vendor icon; a separator above [firstUser], the first of the user's drivers. */
internal fun driverRenderer(firstUser: () -> DbDriverDefinition?): javax.swing.ListCellRenderer<DbDriverDefinition?> = listCellRenderer {
  val driver = value ?: return@listCellRenderer
  text(driver.name)
  icon(PhantomDbIcons.forDriver(driver))
  if (index >= 0 && driver === firstUser()) separator { text = PhantomDbBundle.message("db.drivers.user") }
}
