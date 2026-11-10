// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.erd

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbConsoles
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbSchemaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.nio.file.Files
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton

object ErdFileType : FileType {
  override fun getName(): String = "Phantom ERD"
  override fun getDescription(): String = PhantomDbBundle.message("db.erd.file.description")
  override fun getDefaultExtension(): String = "erd"
  override fun getIcon(): Icon = AllIcons.FileTypes.Diagram
  override fun isBinary(): Boolean = false
}

class ErdEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("erd", ignoreCase = true)
  override fun acceptRequiresReadAction(): Boolean = false
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = ErdEditor(project, file)
  override fun getEditorTypeId(): String = "phantom-erd"

  /** The text tab stays behind the designer: a file with merge conflicts is fixed there. */
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/** New → ERD Design: an empty `.erd` file in the chosen directory. */
class ErdNewFileAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val chosen = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    val dir = if (chosen.isDirectory) chosen else chosen.parent ?: return
    val title = PhantomDbBundle.message("db.erd.new.title")
    val name = Messages.showInputDialog(project, PhantomDbBundle.message("db.erd.new.prompt"), title, null, "schema", null)?.trim()
    if (name.isNullOrEmpty()) return
    val fileName = if (name.endsWith(".erd")) name else "$name.erd"
    val created = runCatching {
      WriteCommandAction.writeCommandAction(project).compute<VirtualFile, Exception> {
        dir.createChildData(this, fileName).also { VfsUtil.saveText(it, ErdJson.write(ErdDocument())) }
      }
    }.getOrElse {
      Messages.showErrorDialog(project, it.message, title)
      return
    }
    FileEditorManager.getInstance(project).openFile(created, true)
  }
}

/**
 * The designer. With no table selected the canvas has the whole tab; a click on a table opens its panel and SQL preview,
 * a click on the empty canvas or the panel's × closes them. Every change is written to the file at once, and the file
 * changing underneath (a pull, the text tab) reloads the design; a file that is not a design is shown as an error and
 * never overwritten.
 */
class ErdEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  internal var document = ErdDocument()
    private set
  private val undo = ArrayDeque<ErdDocument>()
  private val redo = ArrayDeque<ErdDocument>()
  private var selection: String? = null
  private var disposed = false

  internal var live: ErdDocument? = null
    private set
  internal var liveMessage: String? = null
    private set
  private var liveKey: Pair<String?, String?>? = null
  private var liveRequest = 0
  private val sqlPanels = ArrayList<ErdSqlPanel>()

  internal val canvas: ErdCanvas = ErdCanvas(object : ErdCanvas.Listener {
    override fun selected(table: String?) = select(table)
    override fun moved(table: String, x: Int, y: Int) = update(document.updateTable(table) { it.copy(x = x, y = y) })
    override fun linked(child: String, parent: String) {
      relationButton.isSelected = false
      canvas.linking = false
      val changed = document.addRelation(child, parent)
      if (changed == null) notifyUser(PhantomDbBundle.message("db.erd.relation.refused", parent), NotificationType.WARNING)
      else update(changed)
    }
  })
  internal val detail = ErdDetailPanel(object : ErdDetailActions {
    override fun edit(change: (ErdDocument) -> ErdDocument?) {
      val changed = change(document)
      if (changed == null) refresh()
      else {
        // a rename of the selected table keeps it selected
        val index = document.tables.indexOfFirst { it.name == selection }
        update(changed, newSelection = changed.tables.getOrNull(index)?.name)
      }
    }
    override fun close() = select(null)
    override fun deleteTable(name: String) = removeTable(name)
  })
  internal val sql = ErdSqlPanel(this) { selection }

  private val relationButton = JToggleButton(PhantomDbBundle.message("db.erd.relation")).apply {
    name = "erd.relation"
    isFocusable = false
    toolTipText = PhantomDbBundle.message("db.erd.relation.tooltip")
    addActionListener { canvas.linking = isSelected }
  }
  private val dialect = ComboBox(DefaultComboBoxModel(Dbms.entries.filter { it.hasDialect }.toTypedArray())).apply {
    renderer = SimpleListCellRenderer.create("") { it.displayName }
    isFocusable = false
    addActionListener { (selectedItem as? Dbms)?.takeIf { it != document.dbms }?.let { dbms -> this@ErdEditor.update(document.copy(dbms = dbms)) } }
  }
  private val tableAndSql = JBSplitter(true, "phantom.erd.sql", 0.65f)
  private val canvasAndTable = JBSplitter(false, "phantom.erd.detail", 0.65f)
  private val error = JBLabel().apply { horizontalAlignment = JBLabel.CENTER }
  private val cards = CardLayout()
  private val body = JPanel(cards)
  private val root = JPanel(BorderLayout())
  private var unreadable = false

  init {
    canvasAndTable.firstComponent = canvas
    tableAndSql.firstComponent = canvasAndTable
    body.add(tableAndSql, CARD_DESIGN)
    body.add(error, CARD_ERROR)
    root.add(toolbar(), BorderLayout.NORTH)
    root.add(body, BorderLayout.CENTER)
    shortcut(IdeActions.ACTION_UNDO) { undo() }
    shortcut(IdeActions.ACTION_REDO) { redo() }
    shortcut(IdeActions.ACTION_DELETE) { selection?.let { removeTable(it) } }
    project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        if (events.none { it is VFileContentChangeEvent && it.file == file && it.requestor !== this@ErdEditor }) return
        ApplicationManager.getApplication().invokeLater({ if (!disposed && file.isValid) load() }, ModalityState.any())
      }
    })
    load()
    sql.alter.isSelected = document.dataSource != null
  }

  private fun toolbar() = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.customLineBottom(JBColor.border())
    add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
      add(button("db.erd.add.table", "erd.addTable") { addTable() })
      add(relationButton)
      add(button("db.erd.load", "erd.load") { showLoadDialog() })
      add(dialect)
      add(JToggleButton(PhantomDbBundle.message("db.erd.logical")).apply {
        isFocusable = false
        addActionListener { canvas.logicalNames = isSelected }
      })
    }, BorderLayout.WEST)
    add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(4))).apply {
      add(button("db.erd.generate", "erd.generate") { showAllSql() })
    }, BorderLayout.EAST)
  }

  private fun button(key: String, name: String, run: () -> Unit) = JButton(PhantomDbBundle.message(key)).apply {
    this.name = name
    isFocusable = false
    addActionListener { run() }
  }

  private fun shortcut(id: String, run: () -> Unit) {
    val shortcuts = ActionManager.getInstance().getAction(id)?.shortcutSet ?: return
    object : DumbAwareAction() {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = run()
    }.registerCustomShortcutSet(shortcuts, canvas, this)
  }

  private fun load() {
    runCatching { ErdJson.read(VfsUtilCore.loadText(file)) }.onSuccess {
      unreadable = false
      document = it
      undo.clear()
      redo.clear()
      if (document.table(selection.orEmpty()) == null) selection = null
      cards.show(body, CARD_DESIGN)
      refresh()
    }.onFailure {
      unreadable = true
      error.text = PhantomDbBundle.message("db.erd.unreadable", it.message.orEmpty())
      cards.show(body, CARD_ERROR)
    }
  }

  private fun update(changed: ErdDocument, newSelection: String? = selection) {
    if (unreadable) return
    if (changed == document) {
      selection = newSelection?.takeIf { changed.table(it) != null }
      refresh()
      return
    }
    undo.addLast(document)
    if (undo.size > UNDO_LIMIT) undo.removeFirst()
    redo.clear()
    document = changed
    selection = newSelection?.takeIf { changed.table(it) != null }
    save()
    refresh()
  }

  private fun undo() = step(undo, redo)

  private fun redo() = step(redo, undo)

  private fun step(from: ArrayDeque<ErdDocument>, to: ArrayDeque<ErdDocument>) {
    val target = from.removeLastOrNull() ?: return
    to.addLast(document)
    document = target
    if (document.table(selection.orEmpty()) == null) selection = null
    save()
    refresh()
  }

  private fun save() {
    runCatching {
      runWriteAction { file.setBinaryContent(ErdJson.write(document).toByteArray(Charsets.UTF_8), -1, -1, this) }
    }.onFailure { notifyUser(it.message ?: it.javaClass.simpleName, NotificationType.ERROR) }
  }

  private fun refresh() {
    canvas.document = document
    canvas.selection = selection
    if (dialect.selectedItem != document.dbms) dialect.selectedItem = document.dbms
    val open = selection != null
    if ((canvasAndTable.secondComponent != null) != open) canvasAndTable.secondComponent = if (open) detail else null
    if ((tableAndSql.secondComponent != null) != open) tableAndSql.secondComponent = if (open) sql else null
    if (open) detail.display(document, selection)
    refreshSql()
    root.revalidate()
    root.repaint()
  }

  private fun refreshSql() {
    sqlPanels.toList().forEach { it.refresh() }
    if (selection != null) sql.refresh()
  }

  internal fun select(table: String?) {
    selection = table
    refresh()
  }

  internal fun addTable() {
    val center = canvas.center()
    val (changed, name) = document.addTable(center.x, center.y)
    update(changed, newSelection = name)
  }

  private fun removeTable(name: String) = update(document.removeTable(name), newSelection = selection.takeIf { it != name })

  internal fun dataSource(): DbDataSource? =
    document.dataSource?.let { name -> DbDataSourceService.getInstance(project).dataSources.firstOrNull { it.name == name } }

  /** Reads the database ALTER is written against, once per data source and schema unless [force]d; the SQL panels follow. */
  internal fun readLive(force: Boolean = false) {
    val key = document.dataSource to document.schema
    val name = key.first ?: return
    if (!force && key == liveKey && (live != null || liveMessage != null)) return
    liveKey = key
    live = null
    val dataSource = dataSource()
    if (dataSource == null) {
      liveMessage = PhantomDbBundle.message("db.erd.sql.no.datasource", name)
      refreshSql()
      return
    }
    liveMessage = PhantomDbBundle.message("db.erd.sql.reading", name)
    refreshSql()
    val request = ++liveRequest
    DbConnectionService.getInstance(project).launch {
      val result = runCatching { read(dataSource, key.second) }
      withContext(Dispatchers.EDT) {
        if (disposed || request != liveRequest) return@withContext
        live = result.getOrNull()
        liveMessage = result.exceptionOrNull()?.let { "-- " + it.message.orEmpty().replace('\n', ' ') }
        refreshSql()
      }
    }
  }

  private suspend fun read(dataSource: DbDataSource, schema: String?): ErdDocument {
    val ref = DbMetadataCache.getInstance(project).schemas(dataSource).firstOrNull { it.name == schema }
              ?: throw IllegalStateException(PhantomDbBundle.message("db.erd.sql.no.schema", schema.orEmpty()))
    return ErdImport.read(project, dataSource, ref)
  }

  /** What the load dialog ends in: [imported] becomes what ALTER compares with, and with [tables] is laid into the design. */
  internal fun loaded(imported: ErdDocument, tables: Boolean) {
    live = imported
    liveKey = imported.dataSource to imported.schema
    liveMessage = null
    liveRequest++
    update(if (tables) ErdImport.merge(document, imported) else document.copy(dbms = imported.dbms, dataSource = imported.dataSource, schema = imported.schema))
  }

  private fun showLoadDialog() {
    val dialog = ErdLoadDialog(project, dataSource())
    if (!dialog.showAndGet()) return
    val dataSource = dialog.dataSource ?: return
    val schema = dialog.schema ?: return
    val tables = dialog.loadTables
    DbConnectionService.getInstance(project).launch {
      val result = runCatching { ErdImport.read(project, dataSource, schema) }
      withContext(Dispatchers.EDT) {
        if (disposed) return@withContext
        result.onFailure { notifyUser(it.message ?: it.javaClass.simpleName, NotificationType.ERROR) }.onSuccess { imported ->
          val overlap = imported.tables.count { t -> document.tables.any { it.name.equals(t.name, ignoreCase = true) } }
          if (tables && overlap > 0 && Messages.showOkCancelDialog(project, PhantomDbBundle.message("db.erd.load.overwrite", overlap), PhantomDbBundle.message("db.erd.load"),
                                                                   Messages.getOkButton(), Messages.getCancelButton(), null) != Messages.OK) return@onSuccess
          loaded(imported, tables)
        }
      }
    }
  }

  private fun showAllSql() {
    val panel = ErdSqlPanel(this) { null }
    sqlPanels += panel
    panel.refresh()
    try {
      object : DialogWrapper(project, true) {
        init {
          title = PhantomDbBundle.message("db.erd.generate.title")
          init()
        }
        override fun createCenterPanel(): JComponent = panel.apply { preferredSize = JBUI.size(720, 480) }
        override fun createActions(): Array<Action> = arrayOf(okAction)
      }.show()
    }
    finally {
      sqlPanels -= panel
    }
  }

  internal fun projectForSql(): Project = project

  internal fun notifyUser(text: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.erd.file.description"), text, type).notify(project)
  }

  override fun getComponent(): JComponent = root
  override fun getPreferredFocusedComponent(): JComponent = canvas
  override fun getName(): String = PhantomDbBundle.message("db.erd.tab")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = file.isValid
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun getFile(): VirtualFile = file
  override fun dispose() {
    disposed = true
  }

  @TestOnly internal fun selectionForTest(): String? = selection
  @TestOnly internal fun undoForTest() = undo()
  @TestOnly internal fun panelsOpenForTest(): Boolean = canvasAndTable.secondComponent != null && tableAndSql.secondComponent != null
  @TestOnly internal fun errorShownForTest(): Boolean = unreadable

  private companion object {
    const val CARD_DESIGN = "design"
    const val CARD_ERROR = "error"
    const val UNDO_LIMIT = 100
  }
}

/** The SQL of the selected table ([scope]) or of the whole design: CREATE, or ALTER against the design's database. */
internal class ErdSqlPanel(private val editor: ErdEditor, private val scope: () -> String?) : JPanel(BorderLayout()) {
  val alter = JBRadioButton(PhantomDbBundle.message("db.erd.sql.alter"))
  private val create = JBRadioButton(PhantomDbBundle.message("db.erd.sql.create"), true)
  val sqlText = JBTextArea().apply {
    isEditable = false
    font = JBUI.Fonts.create(Font.MONOSPACED, UIUtil.getLabelFont().size)
  }
  private val console = JButton(PhantomDbBundle.message("db.erd.sql.console"))

  init {
    name = "erd.sql"
    ButtonGroup().apply { add(alter); add(create) }
    alter.isSelected = editor.document.dataSource != null
    listOf(alter, create).forEach { it.addActionListener { refresh() } }
    val copy = JButton(PhantomDbBundle.message("db.erd.sql.copy")).apply { addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(sqlText.text)) } }
    val save = JButton(PhantomDbBundle.message("db.erd.sql.save")).apply { addActionListener { saveToFile() } }
    val reread = JButton(PhantomDbBundle.message("db.erd.sql.reread")).apply { addActionListener { editor.readLive(force = true) } }
    console.toolTipText = PhantomDbBundle.message("db.erd.sql.console.tooltip")
    console.addActionListener { editor.dataSource()?.let { DbConsoles.openConsole(editor.projectForSql(), it, sqlText.text) } }
    add(JPanel(BorderLayout()).apply {
      add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { add(alter); add(create); add(reread) }, BorderLayout.WEST)
      add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(2))).apply { add(copy); add(save); add(console) }, BorderLayout.EAST)
    }, BorderLayout.NORTH)
    add(JBScrollPane(sqlText).apply { minimumSize = Dimension(0, 0) }, BorderLayout.CENTER)
  }

  fun refresh() {
    val doc = editor.document
    if (alter.isSelected) editor.readLive()
    console.isEnabled = editor.dataSource() != null
    val sql = when {
      !alter.isSelected -> ErdSql.create(doc, scope())
      doc.dataSource == null -> PhantomDbBundle.message("db.erd.sql.choose")
      else -> editor.live?.let { ErdSql.alter(doc, it, scope()) } ?: editor.liveMessage.orEmpty()
    }
    if (sqlText.text != sql) {
      sqlText.text = sql
      sqlText.caretPosition = 0
    }
  }

  private fun saveToFile() {
    val descriptor = FileSaverDescriptor(PhantomDbBundle.message("db.erd.sql.save"), "", "sql")
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, editor.projectForSql()).save("schema.sql") ?: return
    runCatching { Files.writeString(target.file.toPath(), sqlText.text) }.onFailure { editor.notifyUser(it.message.orEmpty(), NotificationType.ERROR) }
    target.getVirtualFile(true)
  }
}

/** Picks the data source and schema to load from. */
private class ErdLoadDialog(private val project: Project, current: DbDataSource?) : DialogWrapper(project, true) {
  private val sources = ComboBox(DefaultComboBoxModel(DbDataSourceService.getInstance(project).dataSources.toTypedArray())).apply {
    renderer = SimpleListCellRenderer.create("") { it.name }
    current?.let { selectedItem = it }
    addActionListener { loadSchemas() }
  }
  private val schemas = ComboBox(DefaultComboBoxModel<DbSchemaRef>()).apply {
    renderer = SimpleListCellRenderer.create("") { it.name }
  }
  private val tables = JBCheckBox(PhantomDbBundle.message("db.erd.load.tables"), true)
  private var request = 0

  val dataSource: DbDataSource? get() = sources.selectedItem as? DbDataSource
  val schema: DbSchemaRef? get() = schemas.selectedItem as? DbSchemaRef
  val loadTables: Boolean get() = tables.isSelected

  init {
    title = PhantomDbBundle.message("db.erd.load")
    init()
    loadSchemas()
  }

  private fun loadSchemas() {
    val source = dataSource ?: run {
      isOKActionEnabled = false
      return
    }
    val mine = ++request
    isOKActionEnabled = false
    DbConnectionService.getInstance(project).launch {
      val result = runCatching { DbMetadataCache.getInstance(project).schemas(source) }
      withContext(Dispatchers.EDT) {
        if (mine != request) return@withContext
        schemas.model = DefaultComboBoxModel(result.getOrDefault(emptyList()).toTypedArray())
        isOKActionEnabled = schemas.itemCount > 0
        setErrorText(result.exceptionOrNull()?.message)
      }
    }
  }

  override fun createCenterPanel(): JComponent = JPanel(GridLayout(3, 2, JBUI.scale(6), JBUI.scale(4))).apply {
    add(JBLabel(PhantomDbBundle.message("db.erd.load.datasource"))); add(sources)
    add(JBLabel(PhantomDbBundle.message("db.erd.load.schema"))); add(schemas)
    add(JBLabel("")); add(tables)
  }
}
