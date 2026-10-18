// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.DynamicBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.math.BigInteger

@NonNls
private const val BUNDLE = "messages.PhantomMigrationsBundle"

object PhantomMigrationsBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Whether a module depends on a library jar, judged by artifact file name (`flyway-core-10.0.0.jar`). Cached per module. */
object MigrationsGate {
  private val LIBRARIES = Key.create<CachedValue<Set<String>>>("phantom.migrations.libraries")

  fun uses(module: Module, artifact: String): Boolean {
    if (module.isDisposed) return false
    if (ApplicationManager.getApplication().isUnitTestMode) return true
    val names = CachedValuesManager.getManager(module.project).getCachedValue(module, LIBRARIES, {
      val roots = OrderEnumerator.orderEntries(module).recursively().librariesOnly().runtimeOnly().classes().roots
      CachedValueProvider.Result.create(roots.map { it.name }.toSet(), ProjectRootManager.getInstance(module.project))
    }, false)
    return names.any { it.startsWith("$artifact-") }
  }
}

/** A source or resource root of the module, with the path below it. */
internal fun relativeToRoot(module: Module, file: VirtualFile): Pair<VirtualFile, String>? {
  for (root in ModuleRootManager.getInstance(module).getSourceRoots(true)) {
    val relative = VfsUtilCore.getRelativePath(file, root) ?: continue
    return root to relative
  }
  return null
}

/**
 * Flyway's migration locations of a module: `db/migration` plus `spring.flyway.locations` from the module's YAML and
 * properties files (Spring Boot imports such files from several places, so all resource files are read, not only
 * application.yml). Only `classpath:` locations are followed; a `{vendor}` placeholder matches any folder.
 */
object FlywayLocations {
  private val LOCATIONS = Key.create<CachedValue<List<String>>>("phantom.flyway.locations")
  private val PROPERTIES = Regex("""^\s*spring\.flyway\.locations\s*[=:]\s*(.+)$""", RegexOption.MULTILINE)

  fun of(module: Module): List<String> = CachedValuesManager.getManager(module.project).getCachedValue(module, LOCATIONS, {
    val found = linkedSetOf("db/migration")
    val psiManager = PsiManager.getInstance(module.project)
    for (root in ModuleRootManager.getInstance(module).getSourceRoots(false)) {
      VfsUtilCore.iterateChildrenRecursively(root, { it.isDirectory || it.extension in setOf("yml", "yaml", "properties") }) { file ->
        if (!file.isDirectory && file.length < 512 * 1024) {
          val text = VfsUtilCore.loadText(file)
          if (text.contains("flyway")) {
            if (file.extension == "properties") PROPERTIES.findAll(text).forEach { m -> m.groupValues[1].split(',').forEach { found += it } }
            else (psiManager.findFile(file) as? YAMLFile)?.let { yaml -> yamlLocations(yaml).forEach { found += it } }
          }
        }
        true
      }
    }
    val normalized = found.mapNotNull(::normalize).distinct()
    CachedValueProvider.Result.create(normalized, PsiModificationTracker.getInstance(module.project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
  }, false)

  private fun yamlLocations(file: YAMLFile): List<String> = file.documents.flatMap { document ->
    val value = YAMLUtil.getQualifiedKeyInDocument(document, listOf("spring", "flyway", "locations"))?.value
    when (value) {
      is YAMLSequence -> value.items.mapNotNull { (it.value as? YAMLScalar)?.textValue }
      is YAMLScalar -> value.textValue.split(',')
      else -> emptyList()
    }
  }

  /** `classpath:db/{vendor}/migration` becomes a pattern with a one-folder wildcard; filesystem locations are not followed. */
  private fun normalize(location: String): String? {
    val trimmed = location.trim().trim('"', '\'')
    if (trimmed.startsWith("filesystem:")) return null
    val path = trimmed.removePrefix("classpath:").trim('/')
    return path.replace(Regex("""\{[^}]*}"""), "*").ifEmpty { null }
  }

  /** The part of a location before its first wildcard folder. */
  fun fixedPrefix(location: String): String = location.split('/').takeWhile { it != "*" }.joinToString("/")

  private fun matches(location: String, dir: String): Boolean {
    val pattern = location.split('/').joinToString("/") { if (it == "*") "[^/]+" else Regex.escape(it) }
    return Regex("$pattern(/.*)?").matches(dir)
  }

  /** The location a file of a resource root sits in, if any. Flyway also reads the location's subfolders. */
  fun locationOf(module: Module, file: VirtualFile): String? {
    val (_, relative) = relativeToRoot(module, file) ?: return null
    val dir = relative.substringBeforeLast('/', "")
    return of(module).firstOrNull { matches(it, dir) }
  }
}

/** One versioned migration name, e.g. `V1_16__outbox.sql`. Versions compare as numbers, ignoring trailing zero parts. */
data class FlywayName(val prefix: String, val version: String, val description: String) {
  val key: List<BigInteger> get() = version.split('.', '_').map { BigInteger(it) }.dropLastWhile { it == BigInteger.ZERO }

  companion object {
    private val VERSIONED = Regex("""^([VU])(\d+(?:[._]\d+)*)__(.*)\.sql$""")

    fun parse(name: String): FlywayName? = VERSIONED.matchEntire(name)?.let { FlywayName(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
  }
}

/** The versioned migrations of a module's Flyway locations, cached until files are added, removed or renamed. */
object FlywayMigrations {
  private val MIGRATIONS = Key.create<CachedValue<List<Pair<VirtualFile, FlywayName>>>>("phantom.flyway.migrations")

  fun of(module: Module): List<Pair<VirtualFile, FlywayName>> = CachedValuesManager.getManager(module.project).getCachedValue(module, MIGRATIONS, {
    val result = ArrayList<Pair<VirtualFile, FlywayName>>()
    val locations = FlywayLocations.of(module)
    for (root in ModuleRootManager.getInstance(module).getSourceRoots(true)) {
      for (prefix in locations.map(FlywayLocations::fixedPrefix).distinct()) {
        val dir = (if (prefix.isEmpty()) root else root.findFileByRelativePath(prefix)) ?: continue
        VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
          if (!file.isDirectory && FlywayLocations.locationOf(module, file) != null) FlywayName.parse(file.name)?.let { result += file to it }
          true
        }
      }
    }
    CachedValueProvider.Result.create(result.distinctBy { it.first }.toList(), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, PsiModificationTracker.getInstance(module.project))
  }, false)
}

internal fun moduleOf(file: PsiFile): Module? = ModuleUtilCore.findModuleForFile(file)
