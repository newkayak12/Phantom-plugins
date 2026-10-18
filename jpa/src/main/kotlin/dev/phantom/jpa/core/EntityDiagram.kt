// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** One persistent attribute as the diagram shows it. */
data class ErAttribute(val name: String, val type: String, val id: Boolean, val relation: String?)

data class ErEntity(val qualifiedName: String, val name: String, val table: String, val attributes: List<ErAttribute>)

/** [from] `.` [attribute] points at [to]; [kind] is `N:1`, `1:N`, `1:1` or `N:M` seen from [from]. */
data class ErRelation(val from: String, val attribute: String, val to: String, val kind: String)

class ErModel(val entities: List<ErEntity>, val relations: List<ErRelation>) {
  /** A Mermaid `erDiagram`, for pasting into Markdown, GitHub or docs. */
  fun mermaid(): String = buildString {
    append("erDiagram\n")
    for (e in entities) {
      append("    ").append(e.name).append(" {\n")
      for (a in e.attributes.filter { it.relation == null }) {
        append("        ").append(a.type.replace(Regex("[^A-Za-z0-9_]"), "_")).append(' ').append(a.name)
        if (a.id) append(" PK")
        append('\n')
      }
      append("    }\n")
    }
    for (r in relations) {
      val connector = when (r.kind) {
        "N:1" -> "}o--||"
        "1:N" -> "||--o{"
        "1:1" -> "||--||"
        else -> "}o--o{"
      }
      val from = entities.first { it.qualifiedName == r.from }.name
      val to = entities.first { it.qualifiedName == r.to }.name
      append("    $from $connector $to : ${r.attribute}\n")
    }
  }

  companion object {
    private fun ann(vararg simple: String) = simple.flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it") }
    private val ID = ann("Id", "EmbeddedId")
    private val KINDS = linkedMapOf(ann("ManyToOne") to "N:1", ann("OneToMany") to "1:N", ann("OneToOne") to "1:1", ann("ManyToMany") to "N:M")
    private val TABLE = ann("Table")

    /** Call in a read action. Entities of the project's production code; library entities are left out. */
    fun build(project: Project): ErModel {
      val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
      val scope = GlobalSearchScopesCore.projectProductionScope(project)
      val all = GlobalSearchScope.allScope(project)
      val classes = listOf("jakarta.persistence.Entity", "javax.persistence.Entity")
        .flatMap { fqn -> facade.findClasses(fqn, all).flatMap { com.intellij.psi.search.searches.AnnotatedElementsSearch.searchPsiClasses(it, scope).findAll() } }
        .distinctBy { it.qualifiedName }
        .sortedBy { it.name }
      return from(classes)
    }

    fun from(classes: List<PsiClass>): ErModel {
      val names = classes.mapNotNull { it.qualifiedName }.toSet()
      val entities = ArrayList<ErEntity>()
      val relations = ArrayList<ErRelation>()
      for (c in classes) {
        val fqn = c.qualifiedName ?: continue
        val attributes = JpaModel.attributes(c).map { field ->
          val kind = KINDS.entries.firstOrNull { (fqns, _) -> fqns.any { annotation(c, field, it) != null } }?.value
          val target = kind?.let { JpaModel.valueClass(field)?.qualifiedName }?.takeIf { it in names }
          if (kind != null && target != null) {
            // a mappedBy side repeats the owning side's relation: draw the owning one only
            val mappedBy = KINDS.keys.flatten().firstNotNullOfOrNull { annotation(c, field, it) }
              ?.let { AnnotationUtil.getStringAttributeValue(it, "mappedBy") }?.takeIf { it.isNotBlank() }
            if (mappedBy == null || target == fqn) relations.add(ErRelation(fqn, field.name, target, kind))
          }
          ErAttribute(field.name, field.type.presentableText, ID.any { annotation(c, field, it) != null }, target?.substringAfterLast('.'))
        }
        val table = TABLE.firstNotNullOfOrNull { c.getAnnotation(it) }?.let { AnnotationUtil.getStringAttributeValue(it, "name") }
                      ?.takeIf { it.isNotBlank() } ?: snake(JpaModel.entityName(c) ?: c.name ?: "")
        entities.add(ErEntity(fqn, c.name ?: fqn, table, attributes.sortedWith(compareBy({ !it.id }, { it.relation != null }))))
      }
      return ErModel(entities, relations)
    }

    /**
     * The annotation on [field], or on the Kotlin constructor parameter it comes from: an annotation without a
     * use-site target lands on the parameter when its @Target allows that.
     */
    private fun annotation(owner: PsiClass, field: PsiField, fqn: String): com.intellij.psi.PsiAnnotation? =
      field.getAnnotation(fqn) ?: owner.constructors.firstNotNullOfOrNull { ctor ->
        ctor.parameterList.parameters.firstOrNull { it.name == field.name }?.getAnnotation(fqn)
      }

    /** Spring Boot's default physical naming: `TimeTable` → `time_table`. */
    fun snake(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
  }
}

class EntityDiagramToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean =
    com.intellij.openapi.application.readAction { ModuleManager.getInstance(project).modules.any { JpaGate.isJpaModule(it) } }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(EntityDiagramPanel(project, toolWindow.disposable), null, false))
  }
}

/**
 * Entities as boxes (id first, then columns, then relations) with lines for relations, laid out in a grid with
 * related entities next to each other. Built in the background when the window opens and on Refresh; boxes can be
 * dragged, double-click opens the entity, Copy as Mermaid exports the diagram as text.
 */
class EntityDiagramPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
  private var model = ErModel(emptyList(), emptyList())
  private val pointers = HashMap<String, SmartPsiElementPointer<PsiClass>>()
  private val canvas = Canvas()
  private val filter = SearchTextField(false)
  private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(0, 8) }

  init {
    Disposer.register(parent, this)
    filter.textEditor.emptyText.text = "Show entities whose name contains…"
    filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
      override fun textChanged(e: javax.swing.event.DocumentEvent) = canvas.relayout()
    })
    val actions = DefaultActionGroup(
      object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = refresh()
      },
      object : DumbAwareAction("Copy as Mermaid", "Copy the diagram as a Mermaid erDiagram", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(visibleModel().mermaid()))
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar("PhantomEntityDiagram", actions, true).apply { targetComponent = this@EntityDiagramPanel }
    add(JPanel(BorderLayout()).apply {
      add(toolbar.component, BorderLayout.WEST)
      add(filter, BorderLayout.CENTER)
      add(status, BorderLayout.EAST)
    }, BorderLayout.NORTH)
    add(ScrollPaneFactory.createScrollPane(canvas, true), BorderLayout.CENTER)
    refresh()
  }

  private fun refresh() {
    status.text = "Reading entities…"
    ReadAction.nonBlocking<Pair<ErModel, Map<String, SmartPsiElementPointer<PsiClass>>>> {
      val built = ErModel.build(project)
      val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
      val all = GlobalSearchScope.projectScope(project)
      built to built.entities.mapNotNull { e -> facade.findClass(e.qualifiedName, all)?.let { e.qualifiedName to SmartPointerManager.createPointer(it) } }.toMap()
    }
      .inSmartMode(project)
      .expireWith(this)
      .finishOnUiThread(ModalityState.any()) { (built, ptrs) ->
        model = built
        pointers.clear(); pointers.putAll(ptrs)
        status.text = "${built.entities.size} entities · ${built.relations.size} relations"
        canvas.relayout()
      }
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun visibleModel(): ErModel {
    val q = filter.text.trim().lowercase()
    if (q.isEmpty()) return model
    val shown = model.entities.filter { q in it.name.lowercase() || q in it.table.lowercase() }
    val keep = shown.map { it.qualifiedName }.toSet()
    // keep direct neighbours, so a filtered entity still shows what it points to
    val neighbours = model.relations.filter { it.from in keep || it.to in keep }.flatMap { listOf(it.from, it.to) }.toSet()
    val entities = model.entities.filter { it.qualifiedName in keep || it.qualifiedName in neighbours }
    val names = entities.map { it.qualifiedName }.toSet()
    return ErModel(entities, model.relations.filter { it.from in names && it.to in names })
  }

  override fun dispose() {}

  private inner class Canvas : JPanel() {
    private val boxes = LinkedHashMap<String, Rectangle>()
    private var shown = ErModel(emptyList(), emptyList())
    private var dragging: String? = null
    private var dragOffset = Point()

    init {
      background = UIUtil.getPanelBackground()
      val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
          dragging = boxes.entries.lastOrNull { it.value.contains(e.point) }?.key
          dragging?.let { val r = boxes.getValue(it); dragOffset = Point(e.x - r.x, e.y - r.y) }
        }
        override fun mouseDragged(e: MouseEvent) {
          val key = dragging ?: return
          boxes.getValue(key).location = Point(max(0, e.x - dragOffset.x), max(0, e.y - dragOffset.y))
          revalidate(); repaint()
        }
        override fun mouseReleased(e: MouseEvent) { dragging = null }
        override fun mouseClicked(e: MouseEvent) {
          if (e.clickCount != 2) return
          val key = boxes.entries.lastOrNull { it.value.contains(e.point) }?.key ?: return
          (pointers[key]?.element as? Navigatable)?.navigate(true)
        }
      }
      addMouseListener(mouse)
      addMouseMotionListener(mouse)
    }

    private val titleFont get() = JBUI.Fonts.label().asBold()
    private val bodyFont get() = JBUI.Fonts.smallFont()

    fun relayout() {
      shown = visibleModel()
      boxes.clear()
      val fm = getFontMetrics(bodyFont)
      val tm = getFontMetrics(titleFont)
      val line = fm.height
      val order = connectedOrder(shown)
      val columns = max(1, ceil(sqrt(order.size.toDouble())).toInt())
      val gap = JBUI.scale(60)
      var x = gap / 2
      var y = gap / 2
      var rowHeight = 0
      order.forEachIndexed { i, e ->
        val width = max(tm.stringWidth(e.name) + JBUI.scale(16), e.attributes.maxOfOrNull { fm.stringWidth(text(it)) + JBUI.scale(28) } ?: 0)
          .coerceIn(JBUI.scale(140), JBUI.scale(320))
        val height = tm.height + line + JBUI.scale(10) + e.attributes.size * line
        if (i > 0 && i % columns == 0) { x = gap / 2; y += rowHeight + gap; rowHeight = 0 }
        boxes[e.qualifiedName] = Rectangle(x, y, width, height)
        x += width + gap
        rowHeight = max(rowHeight, height)
      }
      revalidate(); repaint()
    }

    /** Entities in breadth-first order over relations, so related ones land in neighbouring cells. */
    private fun connectedOrder(m: ErModel): List<ErEntity> {
      val byName = m.entities.associateBy { it.qualifiedName }
      val adjacency = HashMap<String, MutableSet<String>>()
      for (r in m.relations) { adjacency.getOrPut(r.from) { LinkedHashSet() }.add(r.to); adjacency.getOrPut(r.to) { LinkedHashSet() }.add(r.from) }
      val seen = LinkedHashSet<String>()
      for (start in m.entities.sortedByDescending { adjacency[it.qualifiedName]?.size ?: 0 }) {
        if (start.qualifiedName in seen) continue
        val queue = ArrayDeque(listOf(start.qualifiedName))
        while (queue.isNotEmpty()) {
          val next = queue.removeFirst()
          if (!seen.add(next)) continue
          adjacency[next]?.sorted()?.forEach { if (it !in seen) queue.addLast(it) }
        }
      }
      return seen.mapNotNull { byName[it] }
    }

    private fun text(a: ErAttribute) = "${a.name}: ${a.relation ?: a.type}"

    override fun getPreferredSize(): Dimension {
      val right = boxes.values.maxOfOrNull { it.x + it.width } ?: 0
      val bottom = boxes.values.maxOfOrNull { it.y + it.height } ?: 0
      return Dimension(right + JBUI.scale(40), bottom + JBUI.scale(40))
    }

    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        if (boxes.isEmpty()) {
          g2.color = UIUtil.getContextHelpForeground()
          g2.drawString(if (model.entities.isEmpty()) "No @Entity classes in this project's sources" else "No entity matches the filter", JBUI.scale(16), JBUI.scale(24))
          return
        }
        paintRelations(g2)
        for (e in shown.entities) boxes[e.qualifiedName]?.let { paintEntity(g2, e, it) }
      }
      finally {
        g2.dispose()
      }
    }

    private fun paintRelations(g: Graphics2D) {
      g.font = bodyFont
      g.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.3f)
      for (r in shown.relations) {
        val a = boxes[r.from] ?: continue
        val b = boxes[r.to] ?: continue
        g.color = LINE
        if (r.from == r.to) {
          g.drawArc(a.x + a.width - JBUI.scale(20), a.y - JBUI.scale(14), JBUI.scale(34), JBUI.scale(28), 0, 270)
          continue
        }
        val p1 = edge(a, Point(b.centerX.toInt(), b.centerY.toInt()))
        val p2 = edge(b, Point(a.centerX.toInt(), a.centerY.toInt()))
        g.draw(Line2D.Double(p1, p2))
        val (nearFrom, nearTo) = r.kind.split(':').let { it[0] to it[1] }
        g.color = LABEL
        label(g, nearFrom, p1, p2)
        label(g, nearTo, p2, p1)
        val mid = Point((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
        g.drawString(r.attribute, mid.x + JBUI.scale(4), mid.y - JBUI.scale(4))
      }
    }

    private fun label(g: Graphics2D, text: String, at: Point, toward: Point) {
      val dx = toward.x - at.x
      val dy = toward.y - at.y
      val len = max(1.0, sqrt((dx * dx + dy * dy).toDouble()))
      val x = at.x + (dx / len * JBUI.scale(14)).toInt()
      val y = at.y + (dy / len * JBUI.scale(14)).toInt()
      g.drawString(text, x - g.fontMetrics.stringWidth(text) / 2, y + g.fontMetrics.ascent / 2)
    }

    /** Where the line from the centre of [r] to [target] leaves the box. */
    private fun edge(r: Rectangle, target: Point): Point {
      val cx = r.centerX
      val cy = r.centerY
      val dx = target.x - cx
      val dy = target.y - cy
      if (dx == 0.0 && dy == 0.0) return Point(cx.toInt(), cy.toInt())
      val scale = 1.0 / max(abs(dx) / (r.width / 2.0), abs(dy) / (r.height / 2.0))
      return Point((cx + dx * scale).toInt(), (cy + dy * scale).toInt())
    }

    private fun paintEntity(g: Graphics2D, e: ErEntity, r: Rectangle) {
      val arc = JBUI.scale(8)
      g.color = BOX
      g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
      g.color = HEADER
      val tm = g.getFontMetrics(titleFont)
      val headerHeight = tm.height + g.getFontMetrics(bodyFont).height + JBUI.scale(6)
      g.fillRoundRect(r.x, r.y, r.width, headerHeight, arc, arc)
      g.fillRect(r.x, r.y + headerHeight - arc, r.width, arc)
      g.color = BORDER
      g.drawRoundRect(r.x, r.y, r.width, r.height, arc, arc)
      val pad = JBUI.scale(8)
      g.font = titleFont
      g.color = UIUtil.getLabelForeground()
      g.drawString(e.name, r.x + pad, r.y + tm.ascent + JBUI.scale(3))
      g.font = bodyFont
      val fm = g.fontMetrics
      g.color = UIUtil.getContextHelpForeground()
      g.drawString(e.table, r.x + pad, r.y + tm.height + fm.ascent + JBUI.scale(2))
      var y = r.y + headerHeight + fm.ascent + JBUI.scale(2)
      for (a in e.attributes) {
        g.color = when {
          a.id -> ID_COLOR
          a.relation != null -> RELATION_COLOR
          else -> UIUtil.getLabelForeground()
        }
        val marker = if (a.id) "◆ " else if (a.relation != null) "→ " else "  "
        g.drawString(clip(fm, marker + text(a), r.width - pad * 2), r.x + pad, y)
        y += fm.height
      }
    }

    private fun clip(fm: FontMetrics, s: String, width: Int): String {
      if (fm.stringWidth(s) <= width) return s
      var t = s
      while (t.isNotEmpty() && fm.stringWidth("$t…") > width) t = t.dropLast(1)
      return "$t…"
    }
  }

  private companion object {
    val BOX = JBColor(0xFFFFFF, 0x2B2D30)
    val HEADER = JBColor(0xF1E6D3, 0x3A3227)
    val BORDER = JBColor(0xC9B79C, 0x5C4E3A)
    val LINE = JBColor(0x9E8A6A, 0x8C7A5C)
    val LABEL = JBColor(0x6E5A3C, 0xC8B28A)
    val ID_COLOR = JBColor(0xB5711F, 0xE0A250)
    val RELATION_COLOR = JBColor(0x1565C0, 0x6CA6E8)
  }
}
