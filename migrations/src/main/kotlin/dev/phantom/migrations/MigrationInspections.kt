// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Flyway migration files Flyway skips or rejects: near-miss names (single underscore, lower-case prefix, no
 * description) and two migrations with one version. Only `.sql` files inside the module's Flyway locations are read.
 */
class FlywayMigrationInspection : LocalInspectionTool() {
  private val nearMiss = Regex("""^[VU]\d.*\.sql$|^R_[^_].*\.sql$""")
  private val lowerCase = Regex("""^([vur])(\d|_)""")

  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val vFile = file.virtualFile ?: return null
    if (vFile.extension != "sql") return null
    val module = moduleOf(file) ?: return null
    if (!MigrationsGate.uses(module, "flyway-core")) return null
    FlywayLocations.locationOf(module, vFile) ?: return null
    val message = message(module, vFile) ?: return null
    return arrayOf(manager.createProblemDescriptor(file, message, isOnTheFly, null, ProblemHighlightType.GENERIC_ERROR_OR_WARNING))
  }

  private fun message(module: Module, file: VirtualFile): String? {
    val name = file.name
    val parsed = FlywayName.parse(name)
    if (parsed == null) {
      if (name.startsWith("R__")) return null
      lowerCase.find(name)?.let { return PhantomMigrationsBundle.message("flyway.lower.prefix", name, name.replaceFirstChar(Char::uppercaseChar)) }
      return if (nearMiss.matches(name)) PhantomMigrationsBundle.message("flyway.bad.name", name) else null
    }
    if (parsed.description.isEmpty()) return PhantomMigrationsBundle.message("flyway.empty.description", name)
    val others = FlywayMigrations.of(module).filter { (other, otherName) -> other != file && otherName.prefix == parsed.prefix && otherName.key == parsed.key }
    if (others.isNotEmpty()) return PhantomMigrationsBundle.message("flyway.duplicate", parsed.version.replace('_', '.'), others.joinToString { it.first.name })
    return null
  }
}

/**
 * Liquibase changelogs (XML, YAML, formatted SQL): a changeset id and author used twice in one changelog, and include
 * / includeAll paths that resolve neither next to the changelog nor on the module's resource roots.
 */
class LiquibaseChangelogInspection : LocalInspectionTool() {
  private val sqlHeader = Regex("""^\s*--\s*liquibase\s+formatted\s+sql""", RegexOption.IGNORE_CASE)
  private val sqlChangeset = Regex("""^\s*--\s*changeset\s+([^:\s]+):(\S+)""", RegexOption.IGNORE_CASE)

  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val vFile = file.virtualFile ?: return null
    val problems = ArrayList<ProblemDescriptor>()
    fun report(element: PsiElement, message: String, range: TextRange? = null) {
      problems += manager.createProblemDescriptor(element, range, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
    }
    when {
      file is XmlFile && file.rootTag?.name == "databaseChangeLog" -> {
        val module = moduleOf(file)?.takeIf { MigrationsGate.uses(it, "liquibase-core") } ?: return null
        xml(file.rootTag!!, module, vFile, ::report)
      }
      file is YAMLFile -> {
        val log = file.documents.firstNotNullOfOrNull { (it.topLevelValue as? YAMLMapping)?.getKeyValueByKey("databaseChangeLog") } ?: return null
        val module = moduleOf(file)?.takeIf { MigrationsGate.uses(it, "liquibase-core") } ?: return null
        yaml(log, module, vFile, ::report)
      }
      vFile.extension == "sql" && sqlHeader.containsMatchIn(file.text.trimStart().lineSequence().firstOrNull().orEmpty()) -> {
        moduleOf(file)?.takeIf { MigrationsGate.uses(it, "liquibase-core") } ?: return null
        val seen = HashSet<Pair<String, String>>()
        var offset = 0
        for (line in file.text.split('\n')) {
          sqlChangeset.find(line)?.let { m ->
            val key = m.groupValues[2] to m.groupValues[1]
            if (!seen.add(key)) report(file, PhantomMigrationsBundle.message("liquibase.duplicate", key.first, key.second), TextRange(offset, offset + line.length))
          }
          offset += line.length + 1
        }
      }
    }
    return problems.toTypedArray()
  }

  private fun xml(root: XmlTag, module: Module, changelog: VirtualFile, report: (PsiElement, String, TextRange?) -> Unit) {
    val seen = HashSet<Pair<String, String>>()
    for (tag in root.subTags) {
      when (tag.localName) {
        "changeSet" -> {
          val id = tag.getAttributeValue("id") ?: continue
          val author = tag.getAttributeValue("author").orEmpty()
          if (!seen.add(id to author)) report(tag.getAttribute("id")?.valueElement ?: tag, PhantomMigrationsBundle.message("liquibase.duplicate", id, author), null)
        }
        "include", "includeAll" -> {
          val attribute = if (tag.localName == "include") "file" else "path"
          val path = tag.getAttributeValue(attribute) ?: continue
          val relative = tag.getAttributeValue("relativeToChangelogFile") == "true"
          if (resolveInclude(module, changelog, path, relative, tag.localName == "includeAll") == null) {
            val key = if (tag.localName == "include") "liquibase.missing.file" else "liquibase.missing.dir"
            report(tag.getAttribute(attribute)?.valueElement ?: tag, PhantomMigrationsBundle.message(key, path), null)
          }
        }
      }
    }
  }

  private fun yaml(log: YAMLKeyValue, module: Module, changelog: VirtualFile, report: (PsiElement, String, TextRange?) -> Unit) {
    val seen = HashSet<Pair<String, String>>()
    for (item in (log.value as? YAMLSequence)?.items.orEmpty()) {
      val entry = item.value as? YAMLMapping ?: continue
      entry.getKeyValueByKey("changeSet")?.let { changeSet ->
        val body = changeSet.value as? YAMLMapping ?: return@let
        val idValue = body.getKeyValueByKey("id") ?: return@let
        val id = (idValue.value as? YAMLScalar)?.textValue ?: return@let
        val author = (body.getKeyValueByKey("author")?.value as? YAMLScalar)?.textValue.orEmpty()
        if (!seen.add(id to author)) report(idValue.value ?: idValue, PhantomMigrationsBundle.message("liquibase.duplicate", id, author), null)
      }
      for ((kind, attribute) in listOf("include" to "file", "includeAll" to "path")) {
        val body = entry.getKeyValueByKey(kind)?.value as? YAMLMapping ?: continue
        val pathValue = body.getKeyValueByKey(attribute) ?: continue
        val path = (pathValue.value as? YAMLScalar)?.textValue ?: continue
        val relative = (body.getKeyValueByKey("relativeToChangelogFile")?.value as? YAMLScalar)?.textValue == "true"
        if (resolveInclude(module, changelog, path, relative, kind == "includeAll") == null) {
          val key = if (kind == "include") "liquibase.missing.file" else "liquibase.missing.dir"
          report(pathValue.value ?: pathValue, PhantomMigrationsBundle.message(key, path), null)
        }
      }
    }
  }
}

/** Next to the changelog when relative; otherwise from a resource root, then (as Liquibase also tries) next to the changelog. */
internal fun resolveInclude(module: Module, changelog: VirtualFile, path: String, relative: Boolean, directory: Boolean): VirtualFile? {
  if (path.contains("\${")) return changelog // a property placeholder: cannot be judged
  val clean = path.removePrefix("classpath:").removePrefix("classpath*:")
  val candidates = buildList {
    changelog.parent?.findFileByRelativePath(clean)?.let(::add)
    if (!relative) {
      for (root in sourceAndResourceRoots(module, true)) root.findFileByRelativePath(clean.trimStart('/'))?.let(::add)
    }
  }
  return candidates.firstOrNull { it.isDirectory == directory }
}
