// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger

/**
 * The production source and resource roots of a module. `ModuleRootManager.getSourceRoots` leaves out resource roots,
 * where the configuration files live, so the content entries' source folders are read instead.
 */
object BootRoots {
  fun production(module: Module): List<VirtualFile> =
    ModuleRootManager.getInstance(module).contentEntries.flatMap { entry -> entry.sourceFolders.filter { !it.isTestSource }.mapNotNull { it.file } }

  /** `application*` / `bootstrap*` files in every module's production roots and their `config/` folders. */
  fun baseConfigFiles(project: Project): List<VirtualFile> {
    val out = LinkedHashSet<VirtualFile>()
    for (module in ModuleManager.getInstance(project).modules) {
      for (root in production(module)) {
        for (dir in listOfNotNull(root, root.findChild("config"))) {
          dir.children.filterTo(out) { !it.isDirectory && BootConfigFiles.isConfigFile(it.name) }
        }
      }
    }
    return out.toList()
  }
}

/**
 * Files pulled in with `spring.config.import` (`classpath:kafka/index.yaml`, `optional:file:./local.properties`),
 * followed from the base configuration files and through the imported files themselves. Spring merges them like
 * `application.yml`, so completion, navigation and checks treat them the same. Server imports (`configserver:`) are
 * not files and are left out.
 */
object BootConfigImports {
  private const val IMPORT_KEY = "spring.config.import"
  private val FILE_IMPORT = Regex("""(?:optional:)?(classpath|file):([^\s,'"\]]+\.(?:properties|ya?ml))""")

  @TestOnly
  val computeCount: AtomicInteger = AtomicInteger()

  fun importedFiles(project: Project): Set<VirtualFile> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val base = BootRoots.baseConfigFiles(project)
    val imported = compute(project, base)
    // The imports are read from configuration text only: a Java keystroke must not re-read the files.
    CachedValueProvider.Result.create(imported, *BootCacheDeps.config(project, base + imported))
  }

  private fun compute(project: Project, base: List<VirtualFile>): Set<VirtualFile> {
    computeCount.incrementAndGet()
    val roots = ModuleManager.getInstance(project).modules.flatMap { BootRoots.production(it) }
    // A relative `file:` location is resolved against the working directory, usually the project directory, when it is not
    // beside the importing file; the Spring plugin's SpringConfigKeys looks in the same two places.
    val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
    val seen = HashSet<VirtualFile>(base)
    val imported = LinkedHashSet<VirtualFile>()
    val queue = ArrayDeque(base)
    while (queue.isNotEmpty()) {
      val file = queue.removeFirst()
      val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
      for (match in FILE_IMPORT.findAll(importValues(text, file.name.endsWith(".properties")))) {
        val path = match.groupValues[2]
        val found = if (match.groupValues[1] == "classpath") roots.firstNotNullOfOrNull { it.findFileByRelativePath(path.removePrefix("/")) }
                    else file.parent?.findFileByRelativePath(path.removePrefix("./")) ?: baseDir?.findFileByRelativePath(path.removePrefix("./"))
        if (found != null && !found.isDirectory && seen.add(found)) {
          imported += found
          queue += found
        }
      }
    }
    return imported
  }

  /** The text of the `spring.config.import` value: the properties lines, or the YAML key's line and the lines nested under it. */
  fun importValues(text: String, properties: Boolean): String {
    if (properties) {
      return ConfigText.properties(text).filter { ConfigNames.normalize(it.key).startsWith(IMPORT_KEY) }.joinToString("\n") { it.value }
    }
    val out = StringBuilder()
    var importIndent = -1
    val stack = ArrayList<Pair<Int, String>>()
    for (line in text.lines()) {
      val trimmed = line.trimStart()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      if (line.startsWith("---")) { stack.clear(); importIndent = -1; continue }
      val indent = line.length - trimmed.length
      if (importIndent >= 0) {
        if (indent > importIndent || indent == importIndent && trimmed.startsWith("- ")) { out.append(trimmed).append('\n'); continue }
        importIndent = -1
      }
      if (trimmed.startsWith("-")) continue
      val colon = trimmed.indexOf(':')
      if (colon <= 0) continue
      val key = trimmed.substring(0, colon).trim().trim('"', '\'')
      while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
      stack += indent to key
      if (ConfigNames.normalize(stack.joinToString(".") { it.second }) == IMPORT_KEY) {
        importIndent = indent
        out.append(trimmed.substring(colon + 1)).append('\n')
      }
    }
    return out.toString()
  }
}
