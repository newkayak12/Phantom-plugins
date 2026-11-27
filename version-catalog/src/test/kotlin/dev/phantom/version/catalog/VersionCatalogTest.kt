// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

import com.intellij.testFramework.UsefulTestCase

class VersionCatalogTest : UsefulTestCase() {
  private val catalog = """
    [versions]
    kotlin = "2.0.0"
    jackson = '2.17.0'

    [libraries]
    guava = "com.google.guava:guava:32.0.0-jre"
    kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
    kotlin-reflect = { group = "org.jetbrains.kotlin", name = "kotlin-reflect", version = { ref = "kotlin" } }
    jackson-core = { module = "com.fasterxml.jackson.core:jackson-core", version = "2.16.0" }
    strict = { module = "a:b", version = { strictly = "1.0" } }
    bom = { module = "x:y" }

    [plugins]
    spring = { id = "org.springframework.boot", version = "3.0.0" }
  """.trimIndent()

  fun testParsesAllLibraryForms() {
    val parsed = VersionCatalogParser.parse(catalog)
    assertEquals(mapOf("kotlin" to "2.0.0", "jackson" to "2.17.0"), parsed.versions.mapValues { it.value.value })
    assertEquals(
      listOf(
        "guava com.google.guava:guava 32.0.0-jre null",
        "kotlin-stdlib org.jetbrains.kotlin:kotlin-stdlib null kotlin",
        "kotlin-reflect org.jetbrains.kotlin:kotlin-reflect null kotlin",
        "jackson-core com.fasterxml.jackson.core:jackson-core 2.16.0 null",
        "strict a:b null null",
        "bom x:y null null",
      ),
      parsed.libraries.map { "${it.alias} ${it.coordinate} ${it.version?.value} ${it.versionRef}" },
    )
    for (literal in parsed.versions.values + parsed.libraries.mapNotNull { it.version }) {
      assertEquals(literal.value, catalog.substring(literal.start, literal.end))
    }
  }

  fun testNewestSkipsPreReleasesUnlessCurrentIsOne() {
    val available = listOf("1.0", "1.1", "1.2-RC1", "2.0.0-beta", "1.2.M1")
    assertEquals("1.1", VersionUpdates.newest(available, "1.0"))
    assertEquals("2.0.0-beta", VersionUpdates.newest(available, "1.0-alpha"))
  }

  fun testComputesAndAppliesUpdates() {
    val parsed = VersionCatalogParser.parse(catalog)
    val available = mapOf(
      "com.google.guava:guava" to listOf("32.0.0-jre", "33.3.1-jre"),
      "org.jetbrains.kotlin:kotlin-stdlib" to listOf("2.0.0", "2.1.0", "2.2.0"),
      // The shared "kotlin" entry stops at the version both libraries have.
      "org.jetbrains.kotlin:kotlin-reflect" to listOf("2.0.0", "2.1.0"),
      "com.fasterxml.jackson.core:jackson-core" to listOf("2.16.0"),
    )
    val updates = VersionUpdates.compute(parsed, available)
    assertEquals(listOf("kotlin true 2.1.0", "guava false 33.3.1-jre"), updates.map { "${it.name} ${it.isVersionRef} ${it.newVersion}" })
    val updated = VersionUpdates.apply(catalog, updates)
    assertTrue(updated.contains("kotlin = \"2.1.0\""))
    assertTrue(updated.contains("\"com.google.guava:guava:33.3.1-jre\""))
    assertTrue(updated.contains("version = \"2.16.0\""))
  }

  fun testReadsMavenMetadata() {
    val metadata = "<metadata><versioning><versions><version>1.0</version>\n<version> 1.1 </version></versions></versioning></metadata>"
    assertEquals(listOf("1.0", "1.1"), MavenCentral.versions(metadata))
    assertEquals("https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml", MavenCentral.metadataUrl("com.google.guava", "guava"))
  }
}
