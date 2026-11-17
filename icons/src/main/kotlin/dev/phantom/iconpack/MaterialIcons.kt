// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import javax.swing.Icon

/**
 * File and folder icons by name, in the style of the Material Icon Theme. The rule table is read once, on the first
 * icon request; each SVG is loaded (and cached by IconLoader) only when a file of that kind is shown.
 */
object MaterialIcons {
  private const val ROOT = "/icons/material/"

  private class Rules(val defaultFile: String, val defaultFolder: String, val rootFolder: String, dark: Table, light: Table) {
    val darkTables: List<Table> = listOf(dark)
    val lightTables: List<Table> = listOf(light, dark)
  }

  private class Table(val fileNames: Map<String, String>, val extensions: Map<String, String>, val folders: Map<String, String>)

  private val rules: Rules by lazy(::load)

  fun forFile(file: VirtualFile): Icon? = iconName(file.name)?.let(::icon)

  /** For a path with no file behind it, such as a deleted file in the VCS changes. */
  fun forFileName(fileName: String): Icon? = iconName(fileName)?.let(::icon)

  /**
   * The icon id for a file name. A `.java` file gets the Java cup here; the Java plugin's file icon patcher turns it into
   * the icon of the class it declares, and [JavaFileLogoPatcher] turns it back, so the file keeps the cup as a Kotlin file
   * keeps its logo.
   */
  fun iconName(fileName: String): String? {
    val name = fileName.lowercase()
    for (table in tables()) {
      table.fileNames[name]?.let { return it }
    }
    // `app.module.ts` tries `module.ts`, then `ts`
    var dot = name.indexOf('.', 1)
    while (dot >= 0) {
      val suffix = name.substring(dot + 1)
      for (table in tables()) {
        table.extensions[suffix]?.let { return it }
      }
      dot = name.indexOf('.', dot + 1)
    }
    if (name.startsWith('.')) {
      for (table in tables()) table.extensions[name.substring(1)]?.let { return it }
    }
    return rules.defaultFile
  }

  fun forFolder(folder: VirtualFile, project: Project?): Icon? = folderIconName(folder, project)?.let(::icon)

  fun folderIconName(folder: VirtualFile, project: Project?): String? {
    if (project != null && !project.isDisposed) {
      if (folder.path == project.basePath) return rules.rootFolder
      val index = ProjectFileIndex.getInstance(project)
      if (index.getSourceRootForFile(folder) == folder) {
        val resources = folder.name.equals("resources", ignoreCase = true)
        return when {
          resources -> "folder-resource"
          index.isInTestSourceContent(folder) -> "folder-test"
          else -> "folder-src"
        }
      }
      val named = folderByName(folder.name)
      // A module's content root; IntelliJ marks these too, so they must not look like plain folders
      if (named == null && index.getContentRootForFile(folder) == folder) return "folder-packages"
      // Excluded folders without a name of their own keep the platform's "excluded" look
      if (named == null && index.isExcluded(folder)) return null
      return named ?: rules.defaultFolder
    }
    return folderByName(folder.name) ?: rules.defaultFolder
  }

  private fun folderByName(name: String): String? {
    val key = name.lowercase()
    for (table in tables()) table.folders[key]?.let { return it }
    return null
  }

  private fun tables(): List<Table> = if (JBColor.isBright()) rules.lightTables else rules.darkTables

  private fun icon(name: String): Icon = IconLoader.getIcon("$ROOT$name.svg", MaterialIcons::class.java.classLoader)

  private fun load(): Rules {
    val dark = Array(3) { HashMap<String, String>(1024) }
    val light = Array(3) { HashMap<String, String>() }
    var defaults = listOf("file", "folder", "folder-root")
    val stream = MaterialIcons::class.java.getResourceAsStream("${ROOT}mapping.txt") ?: error("${ROOT}mapping.txt is missing")
    stream.bufferedReader().useLines { lines ->
      for (line in lines) {
        if (line.startsWith('#') || line.isBlank()) continue
        val parts = line.split(' ')
        if (parts[0] == "default") {
          defaults = parts.drop(1)
          continue
        }
        val (kind, key, icon) = parts
        val slot = when (kind.lowercase()) { "f" -> 0; "e" -> 1; else -> 2 }
        (if (kind[0].isUpperCase()) light else dark)[slot][key] = icon
      }
    }
    return Rules(defaults[0], defaults[1], defaults[2],
                 Table(dark[0], dark[1], dark[2]), Table(light[0], light[1], light[2]))
  }
}
