// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import dev.phantom.db.core.drivers.ArtifactFetcher
import dev.phantom.db.core.drivers.MavenCoordinate
import dev.phantom.db.core.drivers.MavenResolver
import dev.phantom.db.core.drivers.ResolvedArtifact
import junit.framework.TestCase
import java.io.File

class MavenResolverTest : TestCase() {
  private val base1 = "https://fake.repo/"
  private val base2 = "https://fake2.repo"  // no trailing slash on purpose

  // Maps URLs under a fake base to a directory laid out like a Maven repository; anything else is a 404.
  private val fetcher = ArtifactFetcher { url ->
    listOf(base1 to "src/test/resources/fake-repo", base2 to "src/test/resources/fake-repo2")
      .firstNotNullOfOrNull { (base, dir) ->
        if (url.startsWith(base)) File(dir, url.removePrefix(base)).takeIf { it.isFile }?.readBytes() else null
      }
  }

  private fun resolver(vararg repos: String = arrayOf(base1)) = MavenResolver(repos.toList(), fetcher)

  private fun resolve(vararg roots: String, repos: Array<String> = arrayOf(base1)): List<ResolvedArtifact> =
    resolver(*repos).resolve(roots.map(MavenCoordinate::parse))

  private fun gavs(resolved: List<ResolvedArtifact>) = resolved.map { "${it.group}:${it.artifact}:${it.version}" }.toSet()

  fun testParentPropertiesAndDependencyManagement() {
    assertEquals(setOf("t:child:1.0", "t:lib:1.5", "t:sibling:1.0"), gavs(resolve("t:child:1.0")))
  }

  fun testBomImport() {
    assertEquals(setOf("t:usesbom:1.0", "t:managed:3.0"), gavs(resolve("t:usesbom:1.0")))
  }

  fun testExclusionsAndOptionalAreDropped() {
    // t:gone is excluded on excl→mid and must stay excluded on mid→kept→gone; x:* drops x:wild
    assertEquals(setOf("t:excl:1.0", "t:mid:1.0", "t:kept:1.0"), gavs(resolve("t:excl:1.0")))
  }

  fun testNearestWins() {
    val resolved = resolve("t:a:1.0")
    assertEquals(setOf("t:a:1.0", "t:b:1.0", "t:c:2.0"), gavs(resolved))
    assertEquals("t:a:1.0", resolved.first().let { "${it.group}:${it.artifact}:${it.version}" })
  }

  fun testScopes() {
    assertEquals(setOf("t:scoped:1.0", "t:rtdep:1.0", "t:compdep:1.0", "t:plaindep:1.0"), gavs(resolve("t:scoped:1.0")))
  }

  fun testLatestVersionFromMetadata() {
    assertEquals("2.0", resolver().latestVersion("t", "meta"))
    assertEquals("1.10", resolver().latestVersion("t", "nolatest"))
    assertEquals(setOf("t:meta:2.0"), gavs(resolve("t:meta")))
  }

  fun testVersionRangePicksHighestWithin() {
    assertEquals(setOf("t:ranged:1.0", "t:meta:1.5"), gavs(resolve("t:ranged:1.0")))
  }

  fun testPomOnlyArtifactHasNoJar() {
    assertEquals(setOf("t:usespom:1.0", "t:viapom:1.0"), gavs(resolve("t:usespom:1.0")))
  }

  fun testEveryPackagingButPomHasJar() {
    assertEquals(setOf("t:mplugin:1.0"), gavs(resolve("t:mplugin:1.0")))
  }

  fun testSecondRepositoryIsUsedWhenFirstMisses() {
    val resolved = resolve("t:onlysecond:1.0", repos = arrayOf(base1, base2))
    assertEquals(setOf("t:onlysecond:1.0", "t:lib:1.5"), gavs(resolved))
    val second = resolved.single { it.artifact == "onlysecond" }
    assertEquals("https://fake2.repo/t/onlysecond/1.0/onlysecond-1.0.jar", second.jarUrl)
    assertEquals("https://fake2.repo/t/onlysecond/1.0/onlysecond-1.0.jar.sha1", second.sha1Url)
    assertEquals("https://fake.repo/t/lib/1.5/lib-1.5.jar", resolved.single { it.artifact == "lib" }.jarUrl)
  }

  fun testCoordinateParsing() {
    assertEquals(MavenCoordinate("g.h", "a", "1"), MavenCoordinate.parse("g.h:a:1"))
    assertEquals(MavenCoordinate("g", "a", null), MavenCoordinate.parse("g:a"))
    assertTrue(runCatching { MavenCoordinate.parse("g") }.exceptionOrNull() is IllegalArgumentException)
  }

  fun testMissingArtifactFails() {
    assertTrue(runCatching { resolve("t:nosuch:1.0") }.exceptionOrNull() is IllegalStateException)
  }
}
