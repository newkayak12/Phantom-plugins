// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel

/** A table in a diagram: its columns (PK flags included) and which of them are in a foreign key. */
data class DbDiagramTable(val ref: DbTableRef, val columns: List<DbColumnInfo>, val fkColumns: Set<String>)

/** What a diagram shows: tables and the foreign keys between them (keys to tables outside it are left out). */
data class DbDiagramModel(val title: String, val tables: List<DbDiagramTable>, val foreignKeys: List<DbForeignKey>, val truncated: Boolean)

/**
 * Layered layout: a table referenced by a foreign key sits in a layer left of the tables referencing it (longest path,
 * cycles and self references ignored); layers are columns, tables in a layer stacked by name.
 */
object DbDiagramLayout {
  const val GAP_X: Int = 80
  const val GAP_Y: Int = 40

  /** Layer of each table: 0 for tables that reference no other table of the diagram. */
  fun layers(model: DbDiagramModel): List<List<DbTableRef>> {
    val refs = model.tables.map { it.ref }.toSet()
    val parents = model.foreignKeys.filter { it.table in refs && it.refTable in refs && it.table != it.refTable }
      .groupBy({ it.table }, { it.refTable })
    val layer = HashMap<DbTableRef, Int>()
    val visiting = HashSet<DbTableRef>()
    fun depth(t: DbTableRef): Int {
      layer[t]?.let { return it }
      if (!visiting.add(t)) return 0
      val d = parents[t].orEmpty().distinct().maxOfOrNull { depth(it) + 1 } ?: 0
      visiting.remove(t)
      layer[t] = d
      return d
    }
    model.tables.sortedBy { it.ref.name.lowercase() }.forEach { depth(it.ref) }
    val count = (layer.values.maxOrNull() ?: -1) + 1
    return (0 until count).map { l -> model.tables.map { it.ref }.filter { layer[it] == l }.sortedBy { it.name.lowercase() } }
  }

  /** Boxes, given each table's size. */
  fun place(model: DbDiagramModel, size: (DbDiagramTable) -> Dimension): Map<DbTableRef, Rectangle> {
    val byRef = model.tables.associateBy { it.ref }
    val result = LinkedHashMap<DbTableRef, Rectangle>()
    var x = GAP_Y
    for (layer in layers(model)) {
      var y = GAP_Y
      var width = 0
      for (ref in layer) {
        val d = size(byRef.getValue(ref))
        result[ref] = Rectangle(x, y, d.width, d.height)
        y += d.height + GAP_Y
        width = maxOf(width, d.width)
      }
      x += width + GAP_X
    }
    return result
  }
}

/** Draws a diagram: boxes with the table name and its columns (PK and FK marked), foreign keys as orthogonal lines. */
class DbDiagramPanel(val model: DbDiagramModel) : JPanel() {
  private val titleFont: Font get() = JBUI.Fonts.label().deriveFont(Font.BOLD)
  private val columnFont: Font get() = JBUI.Fonts.label()
  internal val boxes: MutableMap<DbTableRef, Rectangle> = LinkedHashMap()
  private var zoom = 1.0
  private var offset = Point(0, 0)

  init {
    background = UIUtil.getPanelBackground()
    relayout()
    val mouse = object : MouseAdapter() {
      var dragged: DbTableRef? = null
      var last: Point? = null

      override fun mousePressed(e: MouseEvent) {
        last = e.point
        val p = toModel(e.point)
        dragged = boxes.entries.lastOrNull { it.value.contains(p) }?.key
      }

      override fun mouseDragged(e: MouseEvent) {
        val from = last ?: return
        val dx = e.x - from.x
        val dy = e.y - from.y
        val box = dragged?.let { boxes[it] }
        if (box != null) box.translate((dx / zoom).toInt(), (dy / zoom).toInt())
        else offset.translate(dx, dy)
        // what the integer move dropped is kept for the next event
        last = if (box != null) Point(from.x + ((dx / zoom).toInt() * zoom).toInt(), from.y + ((dy / zoom).toInt() * zoom).toInt()) else e.point
        repaint()
      }

      override fun mouseReleased(e: MouseEvent) {
        dragged = null
        last = null
      }

      override fun mouseWheelMoved(e: MouseWheelEvent) {
        zoomBy(if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1, e.point)
      }
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    addMouseWheelListener(mouse)
  }

  fun relayout() {
    boxes.clear()
    boxes.putAll(DbDiagramLayout.place(model) { size(it, getFontMetrics(titleFont), getFontMetrics(columnFont)) })
    repaint()
  }

  fun zoomBy(factor: Double, at: Point = Point(width / 2, height / 2)) {
    val before = toModel(at)
    zoom = (zoom * factor).coerceIn(0.1, 4.0)
    offset = Point((at.x - before.x * zoom).toInt(), (at.y - before.y * zoom).toInt())
    repaint()
  }

  fun resetZoom() {
    zoom = 1.0
    offset = Point(0, 0)
    repaint()
  }

  private fun toModel(p: Point): Point = Point(((p.x - offset.x) / zoom).toInt(), ((p.y - offset.y) / zoom).toInt())

  private fun lineHeight(fm: FontMetrics) = fm.height + JBUI.scale(2)

  private fun size(t: DbDiagramTable, title: FontMetrics, column: FontMetrics): Dimension {
    val pad = JBUI.scale(8)
    val texts = t.columns.map { columnText(it, t) }
    val width = maxOf(title.stringWidth(t.ref.name), texts.maxOfOrNull { column.stringWidth(it) } ?: 0) + 2 * pad
    val height = lineHeight(title) + pad + t.columns.size * lineHeight(column) + pad
    return Dimension(maxOf(width, JBUI.scale(120)), height)
  }

  private fun columnText(c: DbColumnInfo, t: DbDiagramTable): String {
    val mark = listOfNotNull(if (c.primaryKey) "PK" else null, if (c.name in t.fkColumns) "FK" else null).joinToString(",")
    return (if (mark.isEmpty()) "     " else "$mark ").padEnd(6) + c.name + "  " + c.typeName.lowercase()
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g.create() as Graphics2D
    try {
      g2.translate(offset.x, offset.y)
      g2.scale(zoom, zoom)
      paintDiagram(g2)
    }
    finally {
      g2.dispose()
    }
  }

  /** The whole diagram at 100%, on the editor background. */
  fun toImage(): BufferedImage {
    val bounds = boxes.values.fold(Rectangle()) { acc, r -> if (acc.isEmpty) Rectangle(r) else acc.union(r) }
    val margin = DbDiagramLayout.GAP_Y
    val image = BufferedImage(maxOf(1, bounds.width + 2 * margin), maxOf(1, bounds.height + 2 * margin), BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      g.color = background
      g.fillRect(0, 0, image.width, image.height)
      g.transform(AffineTransform.getTranslateInstance((margin - bounds.x).toDouble(), (margin - bounds.y).toDouble()))
      paintDiagram(g)
    }
    finally {
      g.dispose()
    }
    return image
  }

  private fun paintDiagram(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.stroke = BasicStroke(1.2f)
    g.color = LINE
    for (fk in model.foreignKeys) {
      val from = boxes[fk.table] ?: continue
      val to = boxes[fk.refTable] ?: continue
      drawLink(g, from, to, fk.table == fk.refTable)
    }
    val title = g.getFontMetrics(titleFont)
    val column = g.getFontMetrics(columnFont)
    val pad = JBUI.scale(8)
    for (t in model.tables) {
      val r = boxes[t.ref] ?: continue
      g.color = BOX
      g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8)
      g.color = HEADER
      g.fillRoundRect(r.x, r.y, r.width, lineHeight(title) + pad / 2, 8, 8)
      g.color = BORDER
      g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8)
      g.color = UIUtil.getLabelForeground()
      g.font = titleFont
      g.drawString(t.ref.name, r.x + pad, r.y + pad / 4 + title.ascent)
      g.font = columnFont
      var y = r.y + lineHeight(title) + pad + column.ascent
      for (c in t.columns) {
        g.color = if (c.primaryKey) KEY else if (c.name in t.fkColumns) FOREIGN else UIUtil.getLabelForeground()
        g.drawString(columnText(c, t), r.x + pad, y)
        y += lineHeight(column)
      }
    }
  }

  /** From the referencing box's side to the referenced box's, through a vertical segment between them; an arrow at the end. */
  private fun drawLink(g: Graphics2D, from: Rectangle, to: Rectangle, self: Boolean) {
    val y1 = from.y + JBUI.scale(12)
    val y2 = to.y + JBUI.scale(12)
    if (self) {
      val x = from.x + from.width
      g.drawPolyline(intArrayOf(x, x + 20, x + 20, x), intArrayOf(y1, y1, y1 + 16, y1 + 16), 4)
      return
    }
    val rightward = to.x >= from.x + from.width
    val leftward = to.x + to.width <= from.x
    val (x1, x2, mid) = when {
      leftward -> Triple(from.x, to.x + to.width, (from.x + to.x + to.width) / 2)
      rightward -> Triple(from.x + from.width, to.x, (from.x + from.width + to.x) / 2)
      // overlapping columns: go around on the left
      else -> Triple(from.x, to.x, minOf(from.x, to.x) - 20)
    }
    g.drawPolyline(intArrayOf(x1, mid, mid, x2), intArrayOf(y1, y1, y2, y2), 4)
    val dir = if (x2 >= mid) -1 else 1
    g.fillPolygon(intArrayOf(x2, x2 + dir * 7, x2 + dir * 7), intArrayOf(y2, y2 - 4, y2 + 4), 3)
  }

  companion object {
    private val BOX = JBColor(Color(0xFAFAFA), Color(0x2B2D30))
    private val HEADER = JBColor(Color(0xDDE7F5), Color(0x35425A))
    private val BORDER = JBColor(Color(0x9AA7B8), Color(0x5A6372))
    private val LINE = JBColor(Color(0x6A7A90), Color(0x8C9AAE))
    private val KEY = JBColor(Color(0x9A6700), Color(0xE0B44C))
    private val FOREIGN = JBColor(Color(0x2B6CB0), Color(0x7AA7E0))
  }
}

/** The editor tab's file: carries the model; the diagram exists only while the tab is open. */
class DbDiagramFile(val model: DbDiagramModel) : LightVirtualFile(model.title) {
  init {
    isWritable = false
  }
}

class DbDiagramEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is DbDiagramFile
  override fun acceptRequiresReadAction(): Boolean = false
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = DbDiagramEditor(project, file as DbDiagramFile)
  override fun getEditorTypeId(): String = "phantom-db-diagram"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class DbDiagramEditor(private val project: Project, private val file: DbDiagramFile) : UserDataHolderBase(), FileEditor {
  val diagram = DbDiagramPanel(file.model)
  private val component = JPanel(BorderLayout()).apply {
    val group = DefaultActionGroup(
      action("db.diagram.zoom.in", AllIcons.General.ZoomIn) { diagram.zoomBy(1.25) },
      action("db.diagram.zoom.out", AllIcons.General.ZoomOut) { diagram.zoomBy(1 / 1.25) },
      action("db.diagram.zoom.reset", AllIcons.General.ActualZoom) { diagram.resetZoom() },
      action("db.diagram.relayout", AllIcons.Actions.Refresh) { diagram.relayout() },
      action("db.diagram.export", AllIcons.ToolbarDecorator.Export) { exportPng() },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomDbDiagram", group, true)
    toolbar.targetComponent = diagram
    add(toolbar.component, BorderLayout.NORTH)
    add(diagram, BorderLayout.CENTER)
  }

  private fun action(key: String, icon: javax.swing.Icon, perform: () -> Unit) = object : DumbAwareAction(PhantomDbBundle.message(key), null, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = perform()
  }

  private fun exportPng() {
    val descriptor = FileSaverDescriptor(PhantomDbBundle.message("db.diagram.export"), "", "png")
    val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("${file.model.title}.png") ?: return
    ImageIO.write(diagram.toImage(), "png", target.file)
  }

  override fun getComponent(): JComponent = component
  override fun getPreferredFocusedComponent(): JComponent = diagram
  override fun getName(): String = PhantomDbBundle.message("db.action.diagram.text")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun getFile(): VirtualFile = file
  override fun dispose() {}
}

object DbDiagrams {
  const val MAX_TABLES: Int = 200

  /** Loads the tables' columns and foreign keys (cached, else on the schema-tree connection) and opens the diagram. */
  fun open(project: Project, dataSource: DbDataSource, schema: DbSchemaRef, tables: List<DbTableRef>?) {
    DbConnectionService.getInstance(project).launch {
      val model = try {
        load(project, dataSource, schema, tables)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        val message = (e as? java.sql.SQLException)?.let(DbExecutor::message) ?: e.message ?: e.javaClass.simpleName
        withContext(Dispatchers.EDT) { notify(project, message, NotificationType.ERROR) }
        return@launch
      }
      withContext(Dispatchers.EDT) {
        if (model.truncated) notify(project, PhantomDbBundle.message("db.diagram.truncated", MAX_TABLES), NotificationType.WARNING)
        FileEditorManager.getInstance(project).openFile(DbDiagramFile(model), true)
      }
    }
  }

  suspend fun load(project: Project, dataSource: DbDataSource, schema: DbSchemaRef, tables: List<DbTableRef>?): DbDiagramModel {
    val metadata = DbMetadataCache.getInstance(project)
    val objects = DbObjectCache.getInstance(project)
    val all = (tables ?: metadata.tables(dataSource, schema).filterNot { it.isView })
    val shown = all.take(MAX_TABLES)
    val keys = shown.flatMap { t -> objects.foreignKeys(dataSource, t).map { it.copy(table = t) } }
    val shownSet = shown.toSet()
    val fks = keys.filter { it.refTable in shownSet || shown.any { s -> same(s, it.refTable) } }
      .map { fk -> fk.copy(refTable = shown.firstOrNull { same(it, fk.refTable) } ?: fk.refTable) }
    val diagramTables = shown.map { t ->
      DbDiagramTable(t, metadata.columns(dataSource, t), keys.filter { it.table == t }.flatMap { it.columns }.toSet())
    }
    val title = PhantomDbBundle.message("db.diagram.title", tables?.singleOrNull()?.name ?: schema.name.ifEmpty { dataSource.name })
    return DbDiagramModel(title, diagramTables, fks, all.size > shown.size)
  }

  /** A foreign key's referenced table comes from other metadata calls: its kind may differ; match by name. */
  private fun same(a: DbTableRef, b: DbTableRef) = a.name == b.name && a.schema == b.schema && a.catalog == b.catalog

  private fun notify(project: Project, text: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Phantom Database")
      .createNotification(PhantomDbBundle.message("db.action.diagram.text"), text, type).notify(project)
  }
}
