// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiComment
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile

/**
 * Changelog properties a module defines: `<property name>` (XML), `property: name:` (YAML), `--property name:` (formatted
 * SQL) in any changelog of its source and resource roots (properties are global to the whole changelog tree), keys of
 * `liquibase*.properties` (with or without `parameter.`) and `spring.liquibase.parameters.*` in properties files.
 * [unknowable] is true when parameters come from where they cannot be read: `spring.liquibase.parameters` in YAML, or
 * code that calls `setChangeLogParameters`.
 */
internal class LiquibaseProperties(val names: Set<String>, val unknowable: Boolean) {
  companion object {
    private val KEY = Key.create<CachedValue<LiquibaseProperties>>("phantom.liquibase.properties")
    private val CODE_KEY = Key.create<CachedValue<Boolean>>("phantom.liquibase.parameters.in.code")
    private val XML_PROPERTY = Regex("""<property\b[^>]*?\bname\s*=\s*["']([^"']+)["']""")
    private val YAML_NAME = Regex("""(?m)^\s*-?\s*name\s*:\s*["']?([\w.-]+)""")
    private val SQL_PROPERTY = Regex("""(?im)^\s*--\s*property\s+.*?\bname\s*:\s*(\S+)""")
    private val PROPERTIES_KEY = Regex("""(?m)^\s*([\w.-]+)\s*[=:]""")
    private val SPRING_PARAMETER = Regex("""spring\.liquibase\.parameters\.([\w.-]+)""")

    fun of(module: Module): LiquibaseProperties {
      val fromFiles = fromFiles(module)
      return if (fromFiles.unknowable || !setsParametersInCode(module)) fromFiles else LiquibaseProperties(fromFiles.names, true)
    }

    private fun fromFiles(module: Module): LiquibaseProperties = CachedValuesManager.getManager(module.project).getCachedValue(module, KEY, {
      val names = HashSet<String>()
      var unknowable = false
      val read = ArrayList<VirtualFile>()
      for (root in sourceAndResourceRoots(module, true)) {
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
          if (file.isDirectory) return@iterateChildrenRecursively true
          val ext = file.extension?.lowercase()
          if (ext !in setOf("xml", "yaml", "yml", "sql", "properties")) return@iterateChildrenRecursively true
          read += file
          if (file.length > 2_000_000) return@iterateChildrenRecursively true
          val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@iterateChildrenRecursively true
          when (ext) {
            "xml" -> if (text.contains("databaseChangeLog")) XML_PROPERTY.findAll(text).forEach { names += it.groupValues[1] }
            "yaml", "yml" -> {
              if (text.contains("databaseChangeLog") && text.contains("property:")) YAML_NAME.findAll(text).forEach { names += it.groupValues[1] }
              if (text.contains("liquibase") && text.contains("parameters")) unknowable = true
            }
            "sql" -> SQL_PROPERTY.findAll(text).forEach { names += it.groupValues[1] }
            "properties" -> {
              if (file.name.startsWith("liquibase")) {
                PROPERTIES_KEY.findAll(text).forEach { names += it.groupValues[1]; names += it.groupValues[1].removePrefix("parameter.") }
              }
              SPRING_PARAMETER.findAll(text).forEach { names += it.groupValues[1] }
            }
          }
          true
        }
      }
      // The files are read from disk, so each file read is a dependency (its content on disk), plus files created,
      // deleted or renamed and roots. MODIFICATION_COUNT moved on every keystroke in any language and reread them all.
      CachedValueProvider.Result.create(LiquibaseProperties(names, unknowable), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                                        ProjectRootManager.getInstance(module.project), *read.toTypedArray())
    }, false)

    /**
     * Whether code of the module may set parameters (`setChangeLogParameters`, a `changeLogParameters` property). Kept
     * apart from the file scan: the word can appear in a file of any language, so this cheap index lookup is what still
     * follows every PSI change.
     */
    private fun setsParametersInCode(module: Module): Boolean = CachedValuesManager.getManager(module.project).getCachedValue(module, CODE_KEY, {
      val helper = PsiSearchHelper.getInstance(module.project)
      val scope = GlobalSearchScope.moduleScope(module)
      val found = listOf("setChangeLogParameters", "changeLogParameters").any { word -> !helper.processAllFilesWithWordInText(word, scope, { false }, true) }
      CachedValueProvider.Result.create(found, PsiModificationTracker.getInstance(module.project))
    }, false)
  }
}

/**
 * `${name}` in an XML changelog that no changelog property or parameter defines: Liquibase leaves the text as written,
 * so the SQL runs with a literal `${name}`. Names Liquibase can also take from the environment are not judged: upper-case
 * names (environment variables) and JVM system properties (`user.*`, `java.*`, `os.*`, `file.*`, `line.*`, `path.*`).
 */
class LiquibaseUnresolvedPropertyInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (file !is XmlFile || file.rootTag?.name != "databaseChangeLog") return null
    val module = moduleOf(file)?.takeIf { MigrationsGate.uses(it, "liquibase-core") } ?: return null
    val text = file.text
    if (!text.contains("\${")) return null
    val properties = LiquibaseProperties.of(module)
    if (properties.unknowable) return null
    val own = Regex("""<property\b[^>]*?\bname\s*=\s*["']([^"']+)["']""").findAll(text).map { it.groupValues[1] }.toSet()
    val out = ArrayList<ProblemDescriptor>()
    for (match in PLACEHOLDER.findAll(text)) {
      val name = match.groupValues[1].trim()
      if (name in own || name in properties.names || external(name)) continue
      if (PsiTreeUtil.getParentOfType(file.findElementAt(match.range.first), PsiComment::class.java, false) != null) continue
      out += manager.createProblemDescriptor(file, TextRange(match.range.first, match.range.last + 1),
                                             PhantomMigrationsBundle.message("liquibase.property.unresolved", name),
                                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
    }
    return out.toTypedArray()
  }

  private fun external(name: String): Boolean =
    name.isEmpty() || name.contains(':') || name.none { it.isLowerCase() } || SYSTEM_PREFIXES.any { name.startsWith(it) }

  private companion object {
    val PLACEHOLDER = Regex("""\$\{([^}\s]+)}""")
    val SYSTEM_PREFIXES = listOf("user.", "java.", "os.", "file.", "line.", "path.", "sun.", "liquibase.")
  }
}
