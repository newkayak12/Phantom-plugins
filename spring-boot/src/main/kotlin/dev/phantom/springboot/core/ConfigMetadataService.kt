// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import com.intellij.util.containers.ContainerUtil
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects configuration metadata for a module on first use and keeps it until a root change or a rebuild
 * that rewrites a metadata file. No listeners, no startup work: staleness is checked when keys are requested.
 *
 * Kept in three layers so a Java edit, which only changes the keys read from source, does not merge and sort the
 * thousands of library keys again: libraries (roots and jar timestamps), then the build output and additional metadata
 * (their own stamps), then the source keys laid on top.
 */
@Service(Service.Level.PROJECT)
class ConfigMetadataService(private val project: Project) {
  private class Libraries(val rootsStamp: Long, val roots: List<VirtualFile>, val stamps: List<Long>, val keys: ConfigKeys)
  private class Layout(val stamp: List<Long>, val additional: List<VirtualFile>, val outputs: List<Path>) {
    @Volatile var checked: Checked? = null
  }

  /** The build output files found on disk at [at] (nanoTime), after [generation] builds finished in the IDE. */
  private class Checked(val at: Long, val generation: Long, val files: List<OutputFile>)
  private class Entry(val libraries: Libraries, val localStamp: List<Any>, val belowSource: ConfigKeys, val fromSource: ParsedMetadata, val keys: ConfigKeys)

  // Keyed by the module itself, weakly: a removed or renamed module's entry goes with it.
  private val modules: ConcurrentMap<Module, Entry> = ContainerUtil.createConcurrentWeakMap()
  private val libraryLayers: ConcurrentMap<Module, Libraries> = ContainerUtil.createConcurrentWeakMap()
  private val layouts: ConcurrentMap<Module, Layout> = ContainerUtil.createConcurrentWeakMap()

  /** Parsed library metadata by jar root, invalidated by the jar's timestamp; the least recently used roots are dropped. */
  private val libraryCache = object : LinkedHashMap<String, Pair<Long, ParsedMetadata?>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ParsedMetadata?>>): Boolean = size > MAX_LIBRARY_ROOTS
  }

  private val buildGeneration = AtomicLong()

  /** A build in the IDE finished: its metadata output is looked at on the next request, not after the recheck delay. */
  fun buildFinished() {
    buildGeneration.incrementAndGet()
  }

  fun keysFor(module: Module): ConfigKeys {
    val libraries = libraries(module)
    val local = localMetadata(module)
    val localStamp = local.map { it.stamp }
    val fromSource = SourceConfigProperties.metadata(module)
    val cached = modules[module]
    if (cached != null && cached.libraries === libraries && cached.localStamp == localStamp) {
      if (cached.fromSource === fromSource) return cached.keys
      val keys = cached.belowSource.overlaidBy(fromSource)
      modules[module] = Entry(libraries, localStamp, cached.belowSource, fromSource, keys)
      return keys
    }

    // Keys read from the current sources come first; generated metadata adds descriptions and hints, and may be stale.
    // Same precedence as one flat merge of [source, local..., libraries...], built bottom up.
    val belowSource = local.asReversed().fold(libraries.keys) { below, source -> below.overlaidBy(source.parse()) }
    val keys = belowSource.overlaidBy(fromSource)
    modules[module] = Entry(libraries, localStamp, belowSource, fromSource, keys)
    return keys
  }

  /** The previous single merge of every layer, kept so tests can check the layered result against it. */
  @TestOnly
  fun flatKeysFor(module: Module): ConfigKeys {
    val sources = arrayListOf(SourceConfigProperties.metadata(module))
    localMetadata(module).mapTo(sources) { it.parse() }
    for (root in libraryRoots(module)) libraryMetadata(root)?.let(sources::add)
    return ConfigKeys(sources)
  }

  /** Library keys of [module], merged once per root change; a jar rewritten in place (a snapshot) also counts. */
  private fun libraries(module: Module): Libraries {
    val rootsStamp = ProjectRootModificationTracker.getInstance(project).modificationCount
    libraryLayers[module]?.let { cached ->
      if (cached.rootsStamp == rootsStamp && cached.roots.indices.all { cached.roots[it].isValid && jarStamp(cached.roots[it]) == cached.stamps[it] }) {
        return cached
      }
    }
    libraryMerges.incrementAndGet()
    val roots = libraryRoots(module)
    val stamps = roots.map(::jarStamp)
    val sources = ArrayList<ParsedMetadata>()
    for (root in roots) {
      ProgressManager.checkCanceled()
      libraryMetadata(root)?.let(sources::add)
    }
    return Libraries(rootsStamp, roots, stamps, ConfigKeys(sources)).also { libraryLayers[module] = it }
  }

  private fun libraryRoots(module: Module): List<VirtualFile> =
    OrderEnumerator.orderEntries(module).recursively().librariesOnly().classes().roots.toList()

  private interface LocalSource {
    val stamp: Any
    fun parse(): ParsedMetadata
  }

  private class OutputFile(val path: Path, val mtime: Long) : LocalSource {
    override val stamp: Any get() = "$path@$mtime"
    override fun parse(): ParsedMetadata = try {
      MetadataParser.parse(Files.readString(path))
    }
    catch (_: Exception) {
      ParsedMetadata.EMPTY
    }
  }

  private class SourceFile(val file: VirtualFile) : LocalSource {
    override val stamp: Any get() = "${file.url}@${file.modificationStamp}"
    override fun parse(): ParsedMetadata = MetadataParser.parse(VfsUtilCore.loadText(file))
  }

  /**
   * Metadata generated by spring-boot-configuration-processor into this module's and its dependencies' build output,
   * plus hand-written additional metadata in resource roots (used before the first build).
   * A build writes the output files without telling the VFS, so they are checked on disk: right after a build in the
   * IDE finishes, and otherwise at most once per [RECHECK_NANOS] (a build from a terminal), not on every completion or
   * inspection request. Where to look is cached until roots change or files are created, deleted or renamed.
   */
  private fun localMetadata(module: Module): List<LocalSource> {
    val layout = layout(module)
    val result = ArrayList<LocalSource>()
    layout.additional.filter { it.isValid }.mapTo(result) { SourceFile(it) }
    result.addAll(outputFiles(layout))
    return result
  }

  private fun outputFiles(layout: Layout): List<OutputFile> {
    val now = System.nanoTime()
    val generation = buildGeneration.get()
    layout.checked?.let { if (it.generation == generation && now - it.at < RECHECK_NANOS) return it.files }
    diskChecks.incrementAndGet()
    val files = layout.outputs.mapNotNull { file ->
      try {
        OutputFile(file, Files.getLastModifiedTime(file).toMillis())
      }
      catch (_: Exception) {
        null
      }
    }
    layout.checked = Checked(now, generation, files)
    return files
  }

  private fun layout(module: Module): Layout {
    val stamp = listOf(ProjectRootModificationTracker.getInstance(project).modificationCount,
                       VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount)
    layouts[module]?.let { if (it.stamp == stamp) return it }

    val modules = LinkedHashSet<Module>()
    modules.add(module)
    OrderEnumerator.orderEntries(module).recursively().withoutLibraries().withoutSdk().forEachModule { modules.add(it); true }

    val additional = ArrayList<VirtualFile>()
    val buildDirs = LinkedHashSet<Path>()
    for (m in modules) {
      val roots = ModuleRootManager.getInstance(m)
      for (contentRoot in roots.contentRoots) {
        buildProjectDir(contentRoot)?.let(buildDirs::add)
      }
      for (sourceRoot in roots.sourceRoots) {
        sourceRoot.findFileByRelativePath("META-INF/$ADDITIONAL")?.let(additional::add)
      }
    }
    val outputs = buildDirs.flatMap { dir -> OUTPUT_CANDIDATES.map(dir::resolve) }
    return Layout(stamp, additional, outputs).also { layouts[module] = it }
  }

  /** The Gradle or Maven project directory that owns a content root (`adapter-module/src/main` -> `adapter-module`). */
  private fun buildProjectDir(contentRoot: VirtualFile): Path? {
    var dir: VirtualFile? = contentRoot
    repeat(4) {
      val d = dir ?: return null
      if (BUILD_FILES.any { d.findChild(it) != null }) return d.toNioPathOrNull()
      dir = d.parent
    }
    return null
  }

  private fun VirtualFile.toNioPathOrNull(): Path? = try {
    toNioPath()
  }
  catch (_: UnsupportedOperationException) {
    null
  }

  private fun libraryMetadata(root: VirtualFile): ParsedMetadata? {
    val stamp = jarStamp(root)
    synchronized(libraryCache) { libraryCache[root.url] }?.let { (cachedStamp, parsed) -> if (cachedStamp == stamp) return parsed }
    val files = listOfNotNull(root.findFileByRelativePath("META-INF/$METADATA"), root.findFileByRelativePath("META-INF/$ADDITIONAL"))
    val parsed = if (files.isEmpty()) null
    else {
      val parts = files.map { MetadataParser.parse(VfsUtilCore.loadText(it)) }
      ParsedMetadata(parts.flatMap { it.properties }, parts.fold(emptyMap()) { a, p -> a + p.hints },
                     parts.fold(emptyMap()) { a, p -> a + p.groups })
    }
    synchronized(libraryCache) { libraryCache[root.url] = stamp to parsed }
    return parsed
  }

  companion object {
    private const val METADATA = "spring-configuration-metadata.json"
    private const val ADDITIONAL = "additional-spring-configuration-metadata.json"
    private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle", "pom.xml")
    private val OUTPUT_CANDIDATES = listOf(
      "build/classes/java/main/META-INF/$METADATA",
      "build/classes/kotlin/main/META-INF/$METADATA",
      "build/tmp/kapt3/classes/main/META-INF/$METADATA",
      "build/resources/main/META-INF/$METADATA",
      "target/classes/META-INF/$METADATA",
    )

    private const val MAX_LIBRARY_ROOTS = 2000
    private val RECHECK_NANOS = TimeUnit.SECONDS.toNanos(3)

    private fun jarStamp(root: VirtualFile): Long = (VfsUtilCore.getVirtualFileForJar(root) ?: root).timeStamp

    /** Counts looks at the build output on disk, so tests can tell repeated requests do not touch the disk. */
    @TestOnly
    val diskChecks: AtomicInteger = AtomicInteger()

    @TestOnly
    fun libraryCacheSize(project: Project): Int = getInstance(project).let { synchronized(it.libraryCache) { it.libraryCache.size } }

    /** Counts library-layer merges, so tests can tell a Java edit does not redo it. */
    @TestOnly
    val libraryMerges: AtomicInteger = AtomicInteger()

    fun getInstance(project: Project): ConfigMetadataService = project.service()
  }

}
