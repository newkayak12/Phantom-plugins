// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDriver
import dev.phantom.db.core.DbDrivers
import dev.phantom.db.core.Dbms
import dev.phantom.db.core.drivers.ArtifactFetcher
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverDownloader
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.drivers.MavenResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class DbDriverFilesTest : BasePlatformTestCase() {
  private val base = "https://fake.repo/"

  private fun fetcher(onFetch: (String) -> Unit = {}) = ArtifactFetcher { url ->
    onFetch(url)
    if (url.startsWith(base)) File("src/test/resources/fake-repo", url.removePrefix(base)).takeIf { it.isFile }?.readBytes() else null
  }

  private fun resolver(onFetch: (String) -> Unit = {}) = MavenResolver(listOf(base), fetcher(onFetch))

  private fun definition(id: String, vararg artifacts: String) = DbDriverDefinition().apply {
    this.id = id
    name = id
    driverClass = "org.h2.Driver"
    dialect = Dbms.GENERIC
    this.artifacts = artifacts.toMutableList()
  }

  private fun <T> withTempDir(block: (Path) -> T): T {
    val dir = Files.createTempDirectory("phantom-db-drivers")
    try {
      return block(dir)
    }
    finally {
      FileUtil.delete(dir)
    }
  }

  private fun <T> withRegistered(definition: DbDriverDefinition, block: (DbDataSource) -> T): T {
    val registry = DbDriverRegistry.getInstance()
    registry.save(definition)
    try {
      return block(DbDataSource().apply { dbms = Dbms.GENERIC; url = "jdbc:none:x"; driverId = definition.id })
    }
    finally {
      registry.remove(definition.id)
    }
  }

  private fun filesUnder(dir: Path): List<Path> = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.toList() }

  fun testBuiltInKeepsSingleJarFallback() {
    val found = DbDrivers.find(null, DbDataSource().apply { dbms = Dbms.H2; url = "jdbc:h2:mem:x" })!!
    assertEquals(DbDriver.Origin.LOCAL_CACHE, found.origin)
    assertEquals(1, found.jars.size)
    assertTrue(found.jars.single().name.startsWith("h2-"))
  }

  fun testUserDefinedDriverFromLocalCache() {
    val def = definition("user-h2", "com.h2database:h2")
    val direct = DbDrivers.fromLocalCaches(def)!!
    assertEquals(DbDriver.Origin.LOCAL_CACHE, direct.origin)
    assertTrue(direct.jars.first().name.startsWith("h2-"))
    assertTrue(direct.jars.all { it.exists() })
    withRegistered(def) { ds ->
      assertEquals(direct, DbDrivers.find(null, ds))
    }
    // a root that is in no cache means the step is skipped, not a partial driver
    assertNull(DbDrivers.fromLocalCaches(definition("user-h2-plus", "com.h2database:h2", "no.such.group:nothing")))
  }

  fun testDownloadFromFakeRepoPinsVersion() = withTempDir { target ->
    val def = definition("fake-a", "t:a")
    val result = DbDriverDownloader.download(def, EmptyProgressIndicator(), resolver(), target)
    assertEquals("1.0", result.resolvedVersion)
    assertEquals(listOf("a-1.0.jar", "b-1.0.jar", "c-2.0.jar"), result.resolvedJars.map { Path.of(it).name })
    assertTrue(result.resolvedJars.all { Path.of(it).isRegularFile() && Path.of(it).startsWith(target) })
    assertTrue(target.resolve("t/a/1.0/a-1.0.jar").isRegularFile())
    assertTrue(def.resolvedJars.isEmpty())  // the input is not mutated
    assertTrue(filesUnder(target).none { it.name.endsWith(".part") })
  }

  fun testChecksumMismatchLeavesNothing() = withTempDir { target ->
    val error = runCatching { DbDriverDownloader.download(definition("bad", "t:badsum:1.0"), EmptyProgressIndicator(), resolver(), target) }
      .exceptionOrNull()
    assertTrue(error.toString(), error?.message.orEmpty().contains("Checksum mismatch"))
    assertTrue(filesUnder(target).none { it.name.endsWith(".jar") || it.name.endsWith(".part") })
  }

  fun testCancelLeavesNoPartFile() = withTempDir { target ->
    val indicator = EmptyProgressIndicator()
    val r = resolver { url -> if (url.endsWith(".jar")) indicator.cancel() }
    val error = runCatching { DbDriverDownloader.download(definition("fake-a", "t:a"), indicator, r, target) }.exceptionOrNull()
    assertTrue(error.toString(), error is ProcessCanceledException)
    assertTrue(filesUnder(target).none { it.name.endsWith(".part") })
    assertFalse(target.resolve("t/c/2.0/c-2.0.jar").exists())
  }

  fun testResolvedJarsAreUsedWhenNothingElse() = withTempDir { dir ->
    val jars = listOf(dir.resolve("one-1.0.jar"), dir.resolve("two-1.0.jar")).onEach { Files.writeString(it, "") }
    val def = definition("downloaded-only", "no.such.group:nothing").apply {
      resolvedVersion = "1.0"
      resolvedJars = jars.map { it.toString() }.toMutableList()
    }
    withRegistered(def) { ds ->
      val found = DbDrivers.find(null, ds)!!
      assertEquals(DbDriver.Origin.DOWNLOADED, found.origin)
      assertEquals(jars, found.jars)
      assertEquals("1.0", found.version)
      // a missing downloaded jar means there is no driver; lookup never downloads
      Files.delete(jars[1])
      assertNull(DbDrivers.find(null, ds))
    }
  }

  fun testDefinitionFilesComeBeforeCaches() = withTempDir { dir ->
    val jar = Files.writeString(dir.resolve("custom.jar"), "")
    val def = definition("with-files", "com.h2database:h2").apply { files = mutableListOf(jar.toString()) }
    withRegistered(def) { ds ->
      val found = DbDrivers.find(null, ds)!!
      assertEquals(DbDriver.Origin.CHOSEN, found.origin)
      assertEquals(listOf(jar), found.jars)
    }
  }
}
