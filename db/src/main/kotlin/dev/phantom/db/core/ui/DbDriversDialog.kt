// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.xmlb.XmlSerializer
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverDownloader
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.drivers.DbDriverSettings
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * Drivers: the built-in presets and the user's own drivers on the left, the selected one's editor on the right, and the
 * Maven repositories downloads search. Edits, downloaded jars included, stay in copies until OK.
 */
class DbDriversDialog(private val project: Project?, select: String? = null) : DialogWrapper(project, true) {
  private val registry = DbDriverRegistry.getInstance()
  private val drivers = CollectionListModel(registry.all.map { it.copy() })
  private val list = JBList(drivers)
  internal val editor = DbDriverEditor(project)
  private val removed = HashSet<String>()
  private val repositories = CollectionListModel(listOf(DbDriverDownloader.MAVEN_CENTRAL) + DbDriverSettings.getInstance().repositories)
  private val repositoryList = JBList(repositories)

  init {
    title = PhantomDbBundle.message("db.drivers.title")
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = driverRenderer { drivers.items.firstOrNull { !it.builtIn } }
    list.addListSelectionListener {
      if (it.valueIsAdjusting) return@addListSelectionListener
      editor.commit()
      editor.load(selected())
    }
    editor.onNameChanged = { list.repaint() }
    registry.addListener(disposable) {
      ApplicationManager.getApplication().invokeLater({ if (!isDisposed) pinnedJarsChanged() }, ModalityState.any())
    }
    val index = drivers.items.indexOfFirst { it.id == select }
    list.selectedIndex = if (index >= 0) index else 0
    init()
  }

  override fun createCenterPanel(): JComponent {
    val decorated = ToolbarDecorator.createDecorator(list)
      .disableUpDownActions()
      .setAddAction { add() }
      .setRemoveAction { remove() }
      .setRemoveActionUpdater { selected()?.builtIn == false }
      .addExtraAction(action("db.drivers.duplicate", AllIcons.Actions.Copy, { selected() != null }) { duplicate() })
      .addExtraAction(action("db.drivers.reset", AllIcons.General.Reset, { selected()?.builtIn == true }) { reset() })
      .createPanel()
    val splitter = OnePixelSplitter(false, 0.3f).apply {
      firstComponent = decorated
      secondComponent = editor.component
    }
    val repositoryPanel = ToolbarDecorator.createDecorator(repositoryList)
      .disableUpDownActions()
      .setAddAction {
        Messages.showInputDialog(repositoryList, PhantomDbBundle.message("db.drivers.repository.prompt"), PhantomDbBundle.message("db.drivers.repositories"), null)
          ?.trim()?.takeIf { it.isNotEmpty() && it !in repositories.items }?.let { repositories.add(if (it.endsWith("/")) it else "$it/") }
      }
      .setRemoveAction { repositoryList.selectedIndices.filter { it > 0 }.sortedDescending().forEach(repositories::remove) }
      .setRemoveActionUpdater { repositoryList.selectedIndices.isNotEmpty() && repositoryList.selectedIndices.none { it == 0 } }
      .createPanel()
    return JBTabbedPane().apply {
      addTab(PhantomDbBundle.message("db.drivers.tab"), splitter)
      addTab(PhantomDbBundle.message("db.drivers.repositories"), repositoryPanel)
      preferredSize = java.awt.Dimension(900, 600)
    }
  }

  internal fun selected(): DbDriverDefinition? = list.selectedValue

  private fun select(definition: DbDriverDefinition) {
    list.setSelectedValue(definition, true)
  }

  internal fun add() {
    editor.commit()
    val definition = DbDriverDefinition().apply {
      id = uniqueId("custom", drivers.items.map { it.id }.toSet())
      name = PhantomDbBundle.message("db.drivers.new.name")
      urlTemplate = "jdbc:"
    }
    drivers.add(definition)
    select(definition)
  }

  internal fun duplicate() {
    editor.commit()
    val source = selected() ?: return
    val copy = source.copy().apply {
      id = uniqueId("${source.id}-copy", drivers.items.map { it.id }.toSet())
      name = PhantomDbBundle.message("db.drivers.copy.name", source.name)
      builtIn = false
    }
    drivers.add(copy)
    select(copy)
  }

  /** Removes a user driver; built-ins are only reset. */
  internal fun remove() {
    val definition = selected()?.takeIf { !it.builtIn } ?: return
    val index = drivers.getElementIndex(definition)
    drivers.remove(index)
    removed += definition.id
    if (drivers.size > 0) list.selectedIndex = minOf(index, drivers.size - 1)
    editor.load(selected())
  }

  internal fun reset() {
    val definition = selected()?.takeIf { it.builtIn } ?: return
    val pristine = DbDriverRegistry.builtIns.firstOrNull { it.id == definition.id } ?: return
    val index = drivers.getElementIndex(definition)
    editor.load(null)  // drops pending edits of the old copy
    // the downloaded jars are not an edit: they stay
    drivers.setElementAt(pristine.copy().also { it.resolvedVersion = definition.resolvedVersion; it.resolvedJars = ArrayList(definition.resolvedJars) }, index)
    list.selectedIndex = index
    editor.load(selected())
  }

  /** A download elsewhere (a balloon, Test Connection) finished while the dialog is open: take its jars where this copy has none. */
  private fun pinnedJarsChanged() {
    for (definition in drivers.items) {
      val saved = registry.find(definition.id) ?: continue
      if (definition.resolvedJars.isNotEmpty() || saved.resolvedJars.isEmpty()) continue
      definition.resolvedVersion = saved.resolvedVersion
      definition.resolvedJars = ArrayList(saved.resolvedJars)
    }
    editor.refreshStatus()
  }

  internal fun apply() {
    editor.commit()
    removed.filter { id -> drivers.items.none { it.id == id } }.forEach(registry::remove)
    for (definition in drivers.items) {
      val pristine = if (definition.builtIn) DbDriverRegistry.builtIns.firstOrNull { it.id == definition.id } else null
      if (pristine != null && sameAs(definition, pristine)) {
        registry.resetToDefault(definition.id)
        val saved = registry.find(definition.id)
        if (saved?.resolvedVersion != definition.resolvedVersion || saved?.resolvedJars != definition.resolvedJars) {
          registry.pin(definition.id, definition.resolvedVersion, definition.resolvedJars)
        }
      }
      else registry.save(definition)
    }
    DbDriverSettings.getInstance().repositories = repositories.items.drop(1).toMutableList()
  }

  public override fun doValidate(): ValidationInfo? {
    editor.commit()
    val invalid = drivers.items.firstOrNull { it.name.isBlank() || it.driverClass.isBlank() || it.urlTemplate.isBlank() } ?: return null
    if (selected() !== invalid) select(invalid)
    return ValidationInfo(PhantomDbBundle.message("db.drivers.invalid", invalid.name.ifBlank { invalid.id }))
  }

  override fun doOKAction() {
    apply()
    super.doOKAction()
  }

  /** Equal apart from the download pin, which is not an edit of the definition. */
  private fun sameAs(a: DbDriverDefinition, b: DbDriverDefinition): Boolean {
    fun unpinned(d: DbDriverDefinition) = XmlSerializer.serialize(d.copy().also { it.resolvedVersion = null; it.resolvedJars = ArrayList() })
    return JDOMUtil.areElementsEqual(unpinned(a), unpinned(b))
  }

  private fun action(key: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
    object : DumbAwareAction(PhantomDbBundle.message(key), null, icon) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = enabled()
      }

      override fun actionPerformed(e: AnActionEvent) = perform()
    }
}

/** Replaces each `{name}` in [template] with its value; placeholders without one become empty, never left as `{name}`. */
internal fun fillTemplate(template: String, values: Map<String, String>): String =
  Regex("\\{([^}]+)}").replace(template) { values[it.groupValues[1]].orEmpty() }

/** The URL a data source starts with for this driver: its template on localhost and its default port. */
internal fun DbDriverDefinition.defaultUrl(): String =
  fillTemplate(urlTemplate, mapOf("host" to "localhost", "port" to defaultPort?.toString().orEmpty()))

/** Placeholders the data source dialog has fields for. */
internal val URL_FIELDS: List<String> = listOf("host", "port", "database")

/** True when the data source dialog's fields can fill every placeholder of the URL template (none counts). */
internal fun DbDriverDefinition.urlFromFields(): Boolean =
  Regex("\\{([^}]+)}").findAll(urlTemplate).all { it.groupValues[1] in URL_FIELDS }

/** [base], else `base-2`, `base-3`… */
internal fun uniqueId(base: String, taken: Set<String>): String =
  generateSequence(2) { it + 1 }.map { "$base-$it" }.let { more -> sequenceOf(base) + more }.first { it !in taken }

/**
 * `java.sql.Driver` implementations in [jars]: those named in `META-INF/services/java.sql.Driver`, then any other
 * `*Driver` class that loads (in a throwaway loader) and implements it. This driver's own class comes first.
 */
internal fun DbDriverDefinition.driverClassesIn(jars: List<Path>): List<String> {
  val files = jars.filter { Files.isRegularFile(it) }
  val listed = LinkedHashSet<String>()
  val named = LinkedHashSet<String>()
  for (jar in files) {
    try {
      ZipFile(jar.toFile()).use { zip ->
        zip.getEntry("META-INF/services/java.sql.Driver")?.let { entry ->
          zip.getInputStream(entry).bufferedReader().readLines().map { it.substringBefore('#').trim() }.filterTo(listed) { it.isNotEmpty() }
        }
        zip.entries().asSequence().map { it.name }
          .filter { it.endsWith("Driver.class") && '$' !in it && !it.startsWith("META-INF/") }
          .mapTo(named) { it.removeSuffix(".class").replace('/', '.') }
      }
    }
    catch (_: java.io.IOException) {
      // not a readable jar: nothing to offer from it
    }
  }
  val found = URLClassLoader(files.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
    // a listed class whose dependencies are missing from [jars] is still the jar's own word
    (listed + named).filter { name -> implementsDriver(loader, name) ?: (name in listed) }
  }
  return found.sortedBy { if (it == driverClass) 0 else if (it in listed) 1 else 2 }
}

/** `null` when the class cannot be loaded. */
private fun implementsDriver(loader: ClassLoader, name: String): Boolean? = try {
  val type = Class.forName(name, false, loader)
  java.sql.Driver::class.java.isAssignableFrom(type) && !type.isInterface && !Modifier.isAbstract(type.modifiers)
}
catch (_: ClassNotFoundException) {
  null
}
catch (_: LinkageError) {
  null
}
