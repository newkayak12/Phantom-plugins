// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.io.StringReader
import java.util.Properties

/**
 * The property keys the project's Spring configuration files define, read as text from the root and `config/` folder of
 * every source and resource root (where Spring Boot looks on the classpath). Read when an inspection asks, cached until
 * the next PSI change.
 */
object SpringConfigKeys {
  class Keys(val files: List<VirtualFile>, val normalized: Set<String>, val external: Boolean) {
    /** The key or a key under it is defined, compared the way relaxed binding does (case, dashes and underscores ignored). */
    fun contains(key: String): Boolean {
      val n = normalize(key)
      return n in normalized || normalized.any { it.startsWith("$n.") || it.startsWith("$n[") }
    }
  }

  private val CONFIG_NAME = Regex("""(application|bootstrap)([-_.][\w.-]*)?\.(properties|ya?ml)""")
  private const val PROPERTY_SOURCE = "org.springframework.context.annotation.PropertySource"

  /** Keys that come from somewhere the files do not show: config servers, imports, vault. */
  private val EXTERNAL_MARKERS = listOf("spring.config.import", "spring.cloud.config", "spring.cloud.vault", "spring.cloud.consul",
                                        "spring.config.additional-location", "spring.config.location")

  fun get(project: Project): Keys = CachedValuesManager.getManager(project).getCachedValue(project) {
    CachedValueProvider.Result.create(compute(project), PsiModificationTracker.MODIFICATION_COUNT, ProjectRootManager.getInstance(project))
  }

  private fun compute(project: Project): Keys {
    val withPropertySource = JavaPsiFacade.getInstance(project).findClass(PROPERTY_SOURCE, GlobalSearchScope.allScope(project)) != null
    val files = LinkedHashSet<VirtualFile>()
    for (module in ModuleManager.getInstance(project).modules) {
      for (root in ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.mapNotNull { f -> f.file } }) {
        for (dir in listOfNotNull(root, root.findChild("config"))) {
          for (child in dir.children) {
            if (child.isDirectory) continue
            // @PropertySource may load any properties file on the classpath
            if (CONFIG_NAME.matches(child.name) || (withPropertySource && child.extension == "properties" && !child.name.startsWith("messages"))) files += child
          }
        }
      }
    }
    val keys = HashSet<String>()
    for (file in files) {
      val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
      val raw = if (file.extension == "properties") propertiesKeys(text) else yamlKeys(text)
      raw.mapTo(keys, ::normalize)
    }
    val external = EXTERNAL_MARKERS.map(::normalize).any { marker -> keys.any { it == marker || it.startsWith("$marker.") } }
    return Keys(files.toList(), keys, external)
  }

  fun normalize(key: String): String = key.lowercase().replace("-", "").replace("_", "")

  fun propertiesKeys(text: String): Set<String> = Properties().apply { runCatching { load(StringReader(text)) } }.stringPropertyNames()

  /** Dotted keys of block-style YAML; list items and flow values count for their parent key. Good enough for config files. */
  fun yamlKeys(text: String): Set<String> {
    val keys = HashSet<String>()
    val stack = ArrayList<Pair<Int, String>>()
    var scalarIndent = -1
    for (line in text.lines()) {
      val content = line.trimEnd()
      val trimmed = content.trimStart()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      if (trimmed == "---" || trimmed.startsWith("--- ")) { stack.clear(); scalarIndent = -1; continue }
      val indent = content.length - trimmed.length
      if (scalarIndent >= 0) {
        if (indent > scalarIndent) continue
        scalarIndent = -1
      }
      if (trimmed.startsWith("- ") || trimmed == "-") continue
      val match = Regex("""^("[^"]*"|'[^']*'|[^:#\s][^:#]*?)\s*:(\s|$)(.*)""").find(trimmed) ?: continue
      val key = match.groupValues[1].trim('"', '\'')
      val rest = match.groupValues[3].trim()
      while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
      val full = (stack.map { it.second } + key).joinToString(".")
      keys += full
      if (rest.isEmpty() || rest.startsWith("#")) stack += indent to key
      else if (rest.startsWith("|") || rest.startsWith(">")) scalarIndent = indent
    }
    return keys
  }
}
