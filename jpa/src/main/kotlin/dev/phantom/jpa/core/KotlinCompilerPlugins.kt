// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * What the Kotlin compiler plugins of a module do to JPA classes, read from its Gradle build scripts (and the version
 * catalog behind `alias(libs.plugins.…)`). The `kotlin-jpa` plugin is a no-arg plugin for `@Entity`, `@Embeddable` and
 * `@MappedSuperclass`; it does not open classes. Opening them takes an `allOpen { annotation("…Entity") }` block.
 *
 * Null when the module has no Gradle build script: then nothing is known and the Kotlin checks stay silent.
 */
object KotlinCompilerPlugins {
  class Config(val noArgForEntities: Boolean, val allOpenAnnotations: Set<String>) {
    fun opens(annotationFqns: Collection<String>): Boolean = annotationFqns.any { it in allOpenAnnotations }
  }

  private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
  private val SETTINGS_FILES = listOf("settings.gradle.kts", "settings.gradle")
  private const val JPA_PLUGIN = "org.jetbrains.kotlin.plugin.jpa"
  private val JPA_ANNOTATIONS = listOf("Entity", "Embeddable", "MappedSuperclass").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it") }

  fun of(module: Module): Config? = CachedValuesManager.getManager(module.project).getCachedValue(module) {
    val files = buildFiles(module)
    val config = if (files.none { it.name in BUILD_FILES }) null else read(files)
    CachedValueProvider.Result.create(config, *(files.toTypedArray<Any>() + VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS))
  }

  /** The module's build scripts up to the settings script that ends the build, plus the version catalog. */
  private fun buildFiles(module: Module): List<VirtualFile> {
    val result = LinkedHashSet<VirtualFile>()
    for (root in ModuleRootManager.getInstance(module).contentRoots) {
      var dir: VirtualFile? = root
      var depth = 0
      while (dir != null && depth < 6) {
        BUILD_FILES.mapNotNullTo(result) { dir!!.findChild(it) }
        dir.findChild("gradle")?.findChild("libs.versions.toml")?.let { result.add(it) }
        if (SETTINGS_FILES.any { dir!!.findChild(it) != null }) break
        dir = dir.parent
        depth++
      }
    }
    return result.toList()
  }

  internal fun read(files: List<VirtualFile>): Config {
    val catalog = files.firstOrNull { it.name == "libs.versions.toml" }?.let { catalogPlugins(text(it)) }.orEmpty()
    val scripts = files.filter { it.name in BUILD_FILES }.map(::text)
    return parse(scripts, catalog)
  }

  internal fun parse(scripts: List<String>, catalog: Map<String, String>): Config {
    val applied = HashSet<String>()
    val allOpen = HashSet<String>()
    var noArgEntities = false
    for (script in scripts) {
      applied += appliedPlugins(script, catalog)
      allOpen += blockAnnotations(script, "allOpen")
      if (blockAnnotations(script, "noArg").any { it in JPA_ANNOTATIONS }) noArgEntities = true
    }
    // a `noArg { annotation(…) }` block only compiles with the no-arg plugin applied somewhere in the build
    return Config(JPA_PLUGIN in applied || noArgEntities, allOpen)
  }

  /** Plugin ids a script applies; `alias(…) apply false` and `id(…) apply false` only declare a version. */
  private fun appliedPlugins(script: String, catalog: Map<String, String>): Set<String> {
    val result = HashSet<String>()
    val code = stripComments(script)
    for (line in code.lines()) {
      if (Regex("""\bapply\s*\(?\s*false""").containsMatchIn(line)) continue
      Regex("""\bkotlin\s*\(\s*"([\w.\-]+)"\s*\)""").findAll(line).forEach { result.add("org.jetbrains.kotlin." + it.groupValues[1]) }
      Regex("""\bid\s*\(?\s*["']([\w.\-]+)["']""").findAll(line).forEach { result.add(it.groupValues[1]) }
      Regex("""\bplugin\s*[=:]\s*["']([\w.\-]+)["']""").findAll(line).forEach { result.add(it.groupValues[1]) }
      Regex("""\balias\s*\(\s*libs\.plugins\.([\w.]+)\s*\)""").findAll(line).forEach { m ->
        catalog[normalize(m.groupValues[1])]?.let { result.add(it) }
      }
    }
    return result
  }

  /** `annotation("…")` calls inside `name { … }` blocks (several blocks may exist). */
  private fun blockAnnotations(script: String, name: String): Set<String> {
    val code = stripComments(script)
    val result = HashSet<String>()
    for (start in Regex("""\b$name\s*\{""").findAll(code)) {
      var depth = 0
      var i = start.range.last
      val bodyStart = i + 1
      while (i < code.length) {
        when (code[i]) {
          '{' -> depth++
          '}' -> if (--depth == 0) break
        }
        i++
      }
      val body = code.substring(bodyStart, i.coerceAtMost(code.length))
      Regex("""annotations?\s*\(([^)]*)\)""").findAll(body).forEach { call ->
        Regex("""["']([\w.]+)["']""").findAll(call.groupValues[1]).forEach { result.add(it.groupValues[1]) }
      }
    }
    return result
  }

  /** `[plugins]` of a version catalog: accessor key (dots) → plugin id. */
  internal fun catalogPlugins(toml: String): Map<String, String> {
    val result = HashMap<String, String>()
    var inPlugins = false
    for (raw in toml.lines()) {
      val line = raw.substringBefore('#').trim()
      if (line.startsWith("[")) {
        inPlugins = line == "[plugins]"
        continue
      }
      if (!inPlugins || '=' !in line) continue
      val key = line.substringBefore('=').trim().trim('"')
      val value = line.substringAfter('=')
      val id = Regex("""\bid\s*=\s*"([^"]+)"""").find(value)?.groupValues?.get(1)
               ?: Regex("""^\s*"([^":]+)(:[^"]*)?"""").find(value)?.groupValues?.get(1) ?: continue
      result[normalize(key)] = id
    }
    return result
  }

  private fun normalize(key: String): String = key.replace('-', '.').replace('_', '.').lowercase()

  private fun stripComments(text: String): String =
    text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

  private fun text(file: VirtualFile): String = runCatching { LoadTextUtil.loadText(file).toString() }.getOrDefault("")
}
