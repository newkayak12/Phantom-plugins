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
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDrivers
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbIcons
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverDownloader
import dev.phantom.db.core.drivers.MavenCoordinate
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** The right side of [DbDriversDialog]: edits one driver copy in place on [commit]. */
internal class DbDriverEditor(private val project: Project?) {
  private var definition: DbDriverDefinition? = null
  /** Set while fields are filled from [definition], so their listeners do not write back. */
  private var loading = false
  var onNameChanged: () -> Unit = {}

  private val nameField = JBTextField()
  private val classCombo = ComboBox<String>().apply { isEditable = true }
  private val files = CollectionListModel<String>()
  private val artifacts = CollectionListModel<String>()
  private val artifactList = JBList(artifacts)
  private val versionCombo = ComboBox(CollectionComboBoxModel(mutableListOf(LATEST))).apply { isEditable = true }
  private val versionStatus = JBLabel(" ")
  private val downloadStatus = JBLabel(" ")
  private val urlTemplateField = JBTextField()
  private val prefixesField = JBTextField()
  private val properties = DbPropertiesTable()
  private val dialectCombo = ComboBox(Dbms.entries.toTypedArray()).apply {
    renderer = listCellRenderer {
      text(value.displayName)
      icon(value.icon)
    }
  }

  private val iconCombo = ComboBox(CollectionComboBoxModel(listOf("") + PhantomDbIcons.keys)).apply {
    renderer = listCellRenderer {
      text(PhantomDbIcons.displayName(value))
      icon(PhantomDbIcons.forKey(value))
    }
  }

  val component: JComponent

  init {
    nameField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        if (loading) return
        definition?.name = nameField.text.trim()
        onNameChanged()
      }
    })
    artifactList.addListSelectionListener { if (!it.valueIsAdjusting) showVersions() }
    versionCombo.addActionListener { if (!loading) versionChosen() }
    val fileList = JBList(files)
    val filesPanel = ToolbarDecorator.createDecorator(fileList).disableUpDownActions()
      .setAddAction { chooseFiles() }
      .setRemoveAction { fileList.selectedIndices.sortedDescending().forEach(files::remove); scanClasses() }
      .createPanel()
    val artifactsPanel = ToolbarDecorator.createDecorator(artifactList).disableUpDownActions()
      .setAddAction { addArtifact() }
      .setRemoveAction { artifactList.selectedIndices.sortedDescending().forEach(artifacts::remove) }
      .createPanel()
    val form = panel {
      row(PhantomDbBundle.message("db.drivers.name")) { cell(nameField).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.drivers.class")) { cell(classCombo).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.drivers.files")) { cell(filesPanel).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.drivers.artifacts")) { cell(artifactsPanel).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.drivers.version")) {
        cell(versionCombo)
        button(PhantomDbBundle.message("db.drivers.check.updates")) { checkForUpdates() }
        cell(versionStatus)
      }
      row("") {
        button(PhantomDbBundle.message("db.drivers.download")) { download() }
        cell(downloadStatus).align(AlignX.FILL)
      }
      row(PhantomDbBundle.message("db.drivers.url.template")) {
        cell(urlTemplateField).align(AlignX.FILL).comment(PhantomDbBundle.message("db.drivers.url.template.hint"))
      }
      row(PhantomDbBundle.message("db.drivers.prefixes")) { cell(prefixesField).align(AlignX.FILL).comment(PhantomDbBundle.message("db.drivers.prefixes.hint")) }
      row(PhantomDbBundle.message("db.drivers.properties")) { cell(properties.component).align(AlignX.FILL) }
      row(PhantomDbBundle.message("db.drivers.dialect")) { cell(dialectCombo) }
      row(PhantomDbBundle.message("db.drivers.icon")) { cell(iconCombo) }
    }
    component = JBScrollPane(form).apply { border = null }
    load(null)
  }

  fun load(definition: DbDriverDefinition?) {
    this.definition = definition
    loading = true
    try {
      val d = definition ?: DbDriverDefinition()
      nameField.text = d.name
      classCombo.removeAllItems()
      classCombo.selectedItem = d.driverClass
      files.replaceAll(d.files)
      artifacts.replaceAll(d.artifacts)
      urlTemplateField.text = d.urlTemplate
      prefixesField.text = d.urlPrefixes.joinToString(", ")
      properties.values = d.defaultProperties
      dialectCombo.selectedItem = d.dialect
      iconCombo.selectedItem = d.icon
      versionStatus.text = " "
    }
    finally {
      loading = false
    }
    UIUtil.setEnabled(component, definition != null, true)
    if (artifacts.size > 0) artifactList.selectedIndex = 0 else showVersions()
    refreshStatus()
    scanClasses()
  }

  /** Writes the fields back into the loaded copy. */
  fun commit() {
    val d = definition ?: return
    d.name = nameField.text.trim()
    d.driverClass = (classCombo.editor.item as? String ?: classCombo.selectedItem as? String).orEmpty().trim()
    d.files = files.items.toMutableList()
    d.artifacts = artifacts.items.toMutableList()
    d.urlTemplate = urlTemplateField.text.trim()
    d.urlPrefixes = prefixesField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    d.defaultProperties = LinkedHashMap(properties.values)
    d.dialect = dialectCombo.selectedItem as Dbms
    d.icon = iconCombo.selectedItem as? String ?: ""
  }

  fun refreshStatus() {
    val d = definition
    downloadStatus.text = when {
      d == null -> " "
      d.resolvedJars.isNotEmpty() && d.resolvedJars.all { Files.isRegularFile(Path.of(it)) } ->
        PhantomDbBundle.message("db.drivers.status.downloaded", d.resolvedVersion ?: "?", d.resolvedJars.size)
      else -> PhantomDbBundle.message("db.drivers.status.none")
    }
  }

  private fun chooseFiles() {
    val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withExtensionFilter("jar")
    FileChooser.chooseFiles(descriptor, project, null).map { it.path }.filter { it !in files.items }.forEach(files::add)
    scanClasses()
  }

  private fun addArtifact() {
    val text = Messages.showInputDialog(component, PhantomDbBundle.message("db.drivers.artifact.prompt"), PhantomDbBundle.message("db.drivers.artifacts"), null)
                 ?.trim() ?: return
    if (runCatching { MavenCoordinate.parse(text) }.isFailure) {
      Messages.showErrorDialog(component, PhantomDbBundle.message("db.drivers.artifact.invalid", text), PhantomDbBundle.message("db.drivers.artifacts"))
      return
    }
    artifacts.add(text)
    artifactList.selectedIndex = artifacts.size - 1
  }

  /** The version combo shows the selected artifact's version: `LATEST`, its pinned one, and the downloaded one. */
  private fun showVersions() {
    val coordinate = selectedCoordinate()
    val known = listOfNotNull(LATEST, coordinate?.version, definition?.resolvedVersion?.takeIf { artifactList.selectedIndex == 0 }).distinct()
    loading = true
    try {
      versionCombo.model = CollectionComboBoxModel(known.toMutableList())
      versionCombo.selectedItem = coordinate?.version ?: LATEST
      versionCombo.isEnabled = coordinate != null
      versionStatus.text = " "
    }
    finally {
      loading = false
    }
  }

  private fun versionChosen() {
    val index = artifactList.selectedIndex
    val coordinate = selectedCoordinate() ?: return
    val version = (versionCombo.selectedItem as? String)?.trim().orEmpty()
    val base = "${coordinate.group}:${coordinate.artifact}"
    val updated = if (version.isEmpty() || version == LATEST) base else "$base:$version"
    if (updated != artifacts.getElementAt(index)) artifacts.setElementAt(updated, index)
  }

  private fun selectedCoordinate(): MavenCoordinate? =
    artifactList.selectedValue?.let { runCatching { MavenCoordinate.parse(it) }.getOrNull() }

  private fun checkForUpdates() {
    val coordinate = selectedCoordinate() ?: return
    versionStatus.text = PhantomDbBundle.message("db.drivers.checking")
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.drivers.checking"), true) {
      private var latest: String? = null

      override fun run(indicator: ProgressIndicator) {
        latest = DbDriverDownloader.default().latestVersion(coordinate.group, coordinate.artifact)
      }

      override fun onSuccess() {
        val version = latest ?: return
        val model = versionCombo.model as CollectionComboBoxModel<String>
        if (version !in model.items) model.add(version)
        versionStatus.text = PhantomDbBundle.message("db.drivers.latest", version)
      }

      override fun onThrowable(error: Throwable) {
        versionStatus.text = error.message ?: error.javaClass.simpleName
      }
    })
  }

  /** Downloads the jars of this driver as edited so far; keeps them in the copy, so only OK saves them. Network through [downloader] only. */
  internal var downloader: (DbDriverDefinition, ProgressIndicator) -> DbDriverDefinition = { d, indicator -> DbDriverDownloader.download(d, indicator) }

  internal fun download() {
    commit()
    val d = definition ?: return
    if (d.artifacts.isEmpty()) {
      downloadStatus.text = PhantomDbBundle.message("db.drivers.status.no.artifacts")
      return
    }
    downloadStatus.text = PhantomDbBundle.message("db.drivers.status.downloading")
    val request = d.copy()
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, PhantomDbBundle.message("db.driver.download.title", d.name), true) {
      private var downloaded: DbDriverDefinition? = null

      override fun run(indicator: ProgressIndicator) {
        downloaded = downloader(request, indicator)
      }

      override fun onSuccess() {
        val result = downloaded ?: return
        d.resolvedVersion = result.resolvedVersion
        d.resolvedJars = ArrayList(result.resolvedJars)
        if (definition !== d) return
        refreshStatus()
        showVersions()
        scanClasses()
      }

      override fun onCancel() {
        if (definition === d) refreshStatus()
      }

      override fun onThrowable(error: Throwable) {
        if (definition === d) downloadStatus.text = PhantomDbBundle.message("db.driver.download.failed", d.name, error.message ?: error.javaClass.simpleName)
      }
    })
  }

  /** Fills the class combo from the driver's jars, off the EDT. */
  private fun scanClasses() {
    val d = definition ?: return
    val chosen = files.items.map { Path.of(it) }
    val resolved = d.resolvedJars.map { Path.of(it) }
    ApplicationManager.getApplication().executeOnPooledThread {
      val jars = chosen.flatMap { if (it.isDirectory()) runCatching { it.listDirectoryEntries("*.jar") }.getOrDefault(emptyList()) else listOf(it) }
                   .ifEmpty { resolved.filter { Files.isRegularFile(it) } }
                   .ifEmpty { DbDrivers.fromLocalCaches(d)?.jars.orEmpty() }
      val classes = d.driverClassesIn(jars)
      ApplicationManager.getApplication().invokeLater({
        if (definition !== d) return@invokeLater
        val typed = classCombo.editor.item as? String ?: d.driverClass
        loading = true
        try {
          classCombo.model = CollectionComboBoxModel((classes + listOf(typed).filter { it.isNotBlank() }).distinct().toMutableList())
          classCombo.selectedItem = typed
        }
        finally {
          loading = false
        }
      }, ModalityState.any())
    }
  }

  companion object {
    const val LATEST: String = "LATEST"
  }
}

/** Key/value pairs in a two-column table with add and remove buttons. */
internal class DbPropertiesTable {
  private val model = object : DefaultTableModel(arrayOf(PhantomDbBundle.message("db.properties.name"), PhantomDbBundle.message("db.properties.value")), 0) {}
  private val table = JBTable(model).apply { setShowGrid(false) }

  val component: JComponent = ToolbarDecorator.createDecorator(table).disableUpDownActions()
    .setAddAction {
      model.addRow(arrayOf("", ""))
      table.editCellAt(model.rowCount - 1, 0)
    }
    .setRemoveAction {
      if (table.isEditing) table.cellEditor.cancelCellEditing()
      table.selectedRows.sortedDescending().forEach(model::removeRow)
    }
    .setPreferredSize(java.awt.Dimension(300, 110))
    .createPanel()

  var values: Map<String, String>
    get() {
      if (table.isEditing) table.cellEditor.stopCellEditing()
      return (0 until model.rowCount).associate { (model.getValueAt(it, 0) as String).trim() to (model.getValueAt(it, 1) as String) }
        .filterKeys { it.isNotEmpty() }
    }
    set(value) {
      model.rowCount = 0
      value.forEach { (k, v) -> model.addRow(arrayOf(k, v)) }
    }
}
