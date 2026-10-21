// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.drivers

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.io.HttpRequests
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.DbDrivers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Maven repositories searched after Maven Central when downloading drivers. */
@Service(Service.Level.APP)
@State(name = "PhantomDbDriverSettings", storages = [Storage("phantomDbDrivers.xml")])
class DbDriverSettings : PersistentStateComponent<DbDriverSettings> {
  @XCollection(style = XCollection.Style.v2)
  var repositories: MutableList<String> = ArrayList()

  override fun getState(): DbDriverSettings = this
  override fun loadState(state: DbDriverSettings) {
    repositories = ArrayList(state.repositories)
  }

  companion object {
    fun getInstance(): DbDriverSettings = service()
  }
}

/** Downloads a driver's jars with their dependencies. The only part of driver lookup that touches the network. */
object DbDriverDownloader {
  const val MAVEN_CENTRAL: String = "https://repo1.maven.org/maven2/"

  /**
   * Resolves [definition]'s artifacts and downloads each jar to `<target>/<group>/<artifact>/<version>/<artifact>-<version>.jar`
   * (through a `.part` file, moved into place after its SHA-1 matches). Returns a copy with [DbDriverDefinition.resolvedVersion]
   * and [DbDriverDefinition.resolvedJars] set; the caller saves it through [DbDriverRegistry]. Blocking.
   */
  fun download(
    definition: DbDriverDefinition,
    indicator: ProgressIndicator,
    resolver: MavenResolver = default(),
    target: Path = defaultDir(),
  ): DbDriverDefinition {
    // the legacy built-ins list alternatives, newest first: only the first one is wanted
    val specs = if (definition.id in DbDrivers.LEGACY_IDS) definition.artifacts.take(1) else definition.artifacts
    require(specs.isNotEmpty()) { "${definition.name} has no Maven artifacts to download" }
    val roots = specs.map(MavenCoordinate::parse)
    indicator.checkCanceled()
    val artifacts = resolver.resolve(roots)
    val jars = artifacts.mapIndexed { i, artifact ->
      indicator.checkCanceled()
      indicator.text2 = "${artifact.artifact}-${artifact.version}.jar"
      if (!indicator.isIndeterminate) indicator.fraction = i.toDouble() / artifacts.size
      fetchJar(artifact, resolver.fetcher, indicator, target)
    }
    val first = roots.first()
    return definition.copy().also { copy ->
      copy.resolvedVersion = artifacts.first { it.group == first.group && it.artifact == first.artifact }.version
      copy.resolvedJars = jars.map { it.toString() }.toMutableList()
    }
  }

  fun defaultDir(): Path = PathManager.getSystemDir().resolve("phantom-db/drivers")

  /** Maven Central, then the repositories from [DbDriverSettings], over HTTP. */
  fun default(): MavenResolver {
    val repositories = (listOf(MAVEN_CENTRAL) + DbDriverSettings.getInstance().repositories.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
    return MavenResolver(repositories, ArtifactFetcher { url ->
      try {
        HttpRequests.request(url).productNameAsUserAgent().readBytes(ProgressManager.getInstance().progressIndicator)
      }
      catch (e: HttpRequests.HttpStatusException) {
        if (e.statusCode == 404) null else throw e
      }
    })
  }

  private fun fetchJar(artifact: ResolvedArtifact, fetcher: ArtifactFetcher, indicator: ProgressIndicator, target: Path): Path {
    val dir = target.resolve(artifact.group).resolve(artifact.artifact).resolve(artifact.version)
    val file = dir.resolve("${artifact.artifact}-${artifact.version}.jar")
    if (Files.isRegularFile(file)) return file  // only ever moved into place after a checksum match
    Files.createDirectories(dir)
    val part = dir.resolve("${file.fileName}.part")
    try {
      val bytes = fetcher.fetch(artifact.jarUrl) ?: error("${artifact.jarUrl} not found")
      Files.write(part, bytes)
      indicator.checkCanceled()
      val expected = fetcher.fetch(artifact.sha1Url)?.toString(Charsets.US_ASCII)?.trim()?.split(Regex("\\s+"))?.first()?.lowercase()
      if (expected != null) {
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Checksum mismatch for ${artifact.jarUrl}: expected $expected, got $actual" }
      }
      Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      return file
    }
    finally {
      Files.deleteIfExists(part)
    }
  }
}
