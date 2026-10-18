// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/** Where a key is set: [offset] is where its name (its last segment in `.properties`) starts in [file]. */
class ConfigKeyLocation(val file: VirtualFile, val offset: Int, val value: String)

/**
 * The keys the project's own `application*.yml` / `application*.properties` set, including custom ones no metadata
 * describes (`jwt.secret`), for `@Value("${...}")`. Read from the resource roots of every module, as Spring Boot
 * merges them on the runtime classpath; recomputed after a PSI or VFS change, nothing is indexed.
 */
object ProjectConfigKeys {
  fun keys(project: Project): Map<String, List<ConfigKeyLocation>> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val result = LinkedHashMap<String, MutableList<ConfigKeyLocation>>()
    for (file in configFiles(project)) {
      val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence?.toString()
                 ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
      val entries = if (file.name.endsWith(".properties")) ConfigText.properties(text) else ConfigText.yaml(text)
      for ((key, offset, value) in entries) result.getOrPut(key) { ArrayList() }.add(ConfigKeyLocation(file, offset, value))
    }
    CachedValueProvider.Result.create(result as Map<String, List<ConfigKeyLocation>>, PsiModificationTracker.MODIFICATION_COUNT,
                                      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, ProjectRootManager.getInstance(project))
  }

  /** The locations of [key], matched with relaxed binding (`jwt.secretKey` finds `jwt.secret-key`). */
  fun find(project: Project, key: String): List<ConfigKeyLocation> {
    val all = keys(project)
    all[key]?.let { return it }
    val normalized = ConfigNames.normalize(key)
    return all.entries.filter { ConfigNames.normalize(it.key) == normalized }.flatMap { it.value }
  }

  private fun configFiles(project: Project): List<VirtualFile> {
    val out = LinkedHashSet<VirtualFile>()
    for (module in ModuleManager.getInstance(project).modules) {
      for (root in ModuleRootManager.getInstance(module).getSourceRoots(false)) {
        for (dir in listOfNotNull(root, root.findChild("config"))) {
          dir.children.filterTo(out) { !it.isDirectory && BootConfigFiles.isConfigFile(it.name) }
        }
      }
    }
    return out.toList()
  }
}

/** Flattens configuration text into `(key, offset of the key's last segment, value)`, without a YAML parser. */
object ConfigText {
  data class Entry(val key: String, val offset: Int, val value: String)

  fun properties(text: String): List<Entry> {
    val out = ArrayList<Entry>()
    var offset = 0
    var continued = false
    for (line in text.split('\n')) {
      val start = offset
      offset += line.length + 1
      if (continued) { continued = line.trimEnd().endsWith("\\"); continue }
      continued = line.trimEnd().endsWith("\\")
      val lead = line.length - line.trimStart().length
      val t = line.trim()
      if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue
      val sep = t.indexOfFirst { it == '=' || it == ':' }
      if (sep <= 0) continue
      val key = t.substring(0, sep).trim()
      val segment = key.lastIndexOf('.') + 1
      out.add(Entry(key, start + lead + segment, t.substring(sep + 1).trim().removeSuffix("\\").trim()))
    }
    return out
  }

  /** Every document of a multi-document file; list items and block scalars are skipped. */
  fun yaml(text: String): List<Entry> {
    val out = ArrayList<Entry>()
    val stack = ArrayList<Pair<Int, String>>()
    var blockIndent = -1
    var offset = 0
    for (line in text.split('\n')) {
      val start = offset
      offset += line.length + 1
      if (line.startsWith("---") || line.startsWith("...")) { stack.clear(); blockIndent = -1; continue }
      val content = stripComment(line).trimEnd()
      if (content.isBlank()) continue
      val indent = content.length - content.trimStart().length
      if (blockIndent >= 0) {
        if (indent > blockIndent) continue
        blockIndent = -1
      }
      val body = content.trim()
      if (body.startsWith("-") || body.startsWith("#")) continue
      val colon = keyColon(body)
      if (colon <= 0) continue
      while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
      val rawKey = body.substring(0, colon).trim()
      val key = rawKey.trim('"', '\'').removePrefix("[").removeSuffix("]")
      val value = body.substring(colon + 1).trim()
      val path = (stack.map { it.second } + key).joinToString(".")
      when {
        value.isEmpty() -> stack.add(indent to key)
        value.startsWith("|") || value.startsWith(">") -> { out.add(Entry(path, start + indent, "")); blockIndent = indent }
        else -> out.add(Entry(path, start + indent, unquote(value)))
      }
    }
    return out
  }

  /** The `:` that ends the key: outside quotes and followed by a space or the line end. */
  private fun keyColon(body: String): Int {
    var quote = 0.toChar()
    for (i in body.indices) {
      val c = body[i]
      when {
        quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
        c == '"' || c == '\'' -> if (i == 0) quote = c
        c == ':' && (i + 1 == body.length || body[i + 1] == ' ' || body[i + 1] == '\t') -> return i
      }
    }
    return -1
  }

  private fun stripComment(line: String): String {
    var quote = 0.toChar()
    for (i in line.indices) {
      val c = line[i]
      if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); continue }
      if (c == '"' || c == '\'') quote = c
      else if (c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) return line.substring(0, i)
    }
    return line
  }

  private fun unquote(v: String): String =
    if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) v.substring(1, v.length - 1) else v
}
