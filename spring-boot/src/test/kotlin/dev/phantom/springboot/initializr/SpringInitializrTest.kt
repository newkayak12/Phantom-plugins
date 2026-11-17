// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.initializr

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.ide.starters.remote.SERVER_LANGUAGES
import com.intellij.ide.starters.remote.SERVER_LANGUAGE_LEVEL_KEY
import com.intellij.ide.starters.remote.SERVER_PACKAGE_NAME_KEY
import com.intellij.ide.starters.remote.SERVER_PROJECT_TYPES
import com.intellij.ide.starters.remote.WebStarterContext
import com.intellij.ide.starters.remote.WebStarterFrameworkVersion
import com.intellij.ide.starters.shared.DependencyAvailable
import com.intellij.ide.starters.shared.DependencyUnavailable
import com.intellij.ide.starters.shared.KOTLIN_STARTER_LANGUAGE
import com.intellij.ide.starters.shared.StarterConfigFileFormat
import com.intellij.ide.starters.shared.StarterProjectType
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Version ranges, metadata parsing and the generator query, without a network. */
class SpringInitializrTest : BasePlatformTestCase() {
  private fun v(text: String) = BootVersion.parse(text) ?: error("unparsed $text")

  fun testNewProjectWizardListsSpringBoot() {
    val builder = ModuleBuilder.getAllBuilders().filterIsInstance<SpringInitializrModuleBuilder>().single()
    assertEquals("Spring Boot", builder.presentableName)
  }

  fun testPreReleasesSortBelowTheRelease() {
    val ordered = listOf("4.1.1", "4.2.0-M1", "4.2.0-M2", "4.2.0-RC1", "4.2.0-SNAPSHOT", "4.2.0", "4.10.0")
    assertEquals(ordered, ordered.shuffled().sortedBy(::v))
    assertEquals(0, v("2.7.0.RELEASE").compareTo(v("2.7.0")))
    assertTrue(v("2.7.1.BUILD-SNAPSHOT") < v("2.7.1"))
    assertNull(BootVersion.parse("latest"))
  }

  fun testRanges() {
    val bounded = BootVersionRange.parse("[4.0.0,4.3.0-M1)")!!
    assertTrue(v("4.0.0") in bounded)
    assertTrue(v("4.2.0") in bounded)
    assertFalse(v("4.3.0-M1") in bounded)
    assertFalse(v("3.5.6") in bounded)
    assertEquals(">= 4.0.0 and < 4.3.0-M1", bounded.describe())

    val open = BootVersionRange.parse("4.0.0")!!
    assertTrue(v("5.0.0") in open)
    assertFalse(v("4.0.0-RC1") in open)
    assertEquals(">= 4.0.0", open.describe())

    val exclusiveLower = BootVersionRange.parse("(3.5.0,4.0.0]")!!
    assertFalse(v("3.5.0") in exclusiveLower)
    assertTrue(v("4.0.0") in exclusiveLower)
    assertNull(BootVersionRange.parse("[nonsense"))
  }

  fun testMetadata() {
    val options = InitializrMetadata.parse(ObjectMapper().readTree(METADATA))
    assertEquals(listOf("4.2.0-M2", "4.1.1"), options.frameworkVersions.map { it.id })
    assertEquals("4.1.1", options.frameworkVersions.single { it.isDefault }.id)

    val web = options.dependencyCategories.single { it.title == "Web" }.dependencies
    assertEquals(listOf("web", "restclient"), web.map { it.id })
    val restClient = web[1] as InitializrDependency
    assertFalse(v("3.5.6") in restClient.versionRange!!)
    assertEquals("https://docs.spring.io/spring-boot/4.1.1/reference/web.html", web[0].links.single().url)

    // the lone-build-file types are not projects
    assertEquals(listOf(StarterProjectType("gradle-project-kotlin", "Gradle - Kotlin"), StarterProjectType("maven-project", "Maven")),
                 options.getUserData(SERVER_PROJECT_TYPES))
    assertEquals(listOf("java", "kotlin"), options.getUserData(SERVER_LANGUAGES)!!.map { it.id })
    assertEquals("21", options.getUserData(SERVER_LANGUAGE_LEVEL_KEY)!!.id)
    assertEquals("com.example.demo", options.getUserData(SERVER_PACKAGE_NAME_KEY))
  }

  fun testDependencyStateFollowsTheChosenBootVersion() {
    val options = InitializrMetadata.parse(ObjectMapper().readTree(METADATA))
    val restClient = options.dependencyCategories.flatMap { it.dependencies }.single { it.id == "restclient" }
    val web = options.dependencyCategories.flatMap { it.dependencies }.single { it.id == "web" }
    assertTrue(SpringInitializrModuleBuilder.dependencyState("4.2.0-M2", restClient) is DependencyAvailable)
    assertTrue(SpringInitializrModuleBuilder.dependencyState("4.1.1", restClient) is DependencyAvailable)
    val old = SpringInitializrModuleBuilder.dependencyState("3.5.6", restClient)
    assertEquals("Requires Spring Boot >= 4.0.0", (old as DependencyUnavailable).message)
    assertTrue(SpringInitializrModuleBuilder.dependencyState("3.5.6", web) is DependencyAvailable)
  }

  fun testGeneratorQuery() {
    val context = WebStarterContext().apply {
      language = KOTLIN_STARTER_LANGUAGE
      projectType = StarterProjectType("gradle-project-kotlin", "Gradle - Kotlin")
      group = "dev.example"
      artifact = "booking"
      version = "0.0.1-SNAPSHOT"
      packageName = "dev.example.booking"
      languageLevel = InitializrMetadata.javaLevel("21")
      configFileFormat = StarterConfigFileFormat("yaml", "YAML")
      frameworkVersion = WebStarterFrameworkVersion("4.1.1", "4.1.1", true)
      dependencies += InitializrDependency("web", "Spring Web", null, emptyList(), null)
      dependencies += InitializrDependency("data-jpa", "Spring Data JPA", null, emptyList(), null)
    }
    val query = SpringInitializrModuleBuilder.generatorParameters(context)
    assertEquals("gradle-project-kotlin", query["type"])
    assertEquals("kotlin", query["language"])
    assertEquals("4.1.1", query["bootVersion"])
    assertEquals("booking", query["baseDir"])
    assertEquals("21", query["javaVersion"])
    assertEquals("yaml", query["configurationFileFormat"])
    assertEquals("data-jpa,web", query["dependencies"])
    assertNull(query["packaging"])
  }

  companion object {
    private val METADATA = """
      {
        "dependencies": {"type": "hierarchical-multi-select", "values": [
          {"name": "Web", "values": [
            {"id": "web", "name": "Spring Web", "description": "Build web apps",
             "_links": {"reference": {"href": "https://docs.spring.io/spring-boot/{bootVersion}/reference/web.html", "templated": true}}},
            {"id": "restclient", "name": "RestClient", "versionRange": "4.0.0"}
          ]},
          {"name": "Empty", "values": []}
        ]},
        "type": {"type": "action", "default": "gradle-project-kotlin", "values": [
          {"id": "gradle-project-kotlin", "name": "Gradle - Kotlin", "tags": {"build": "gradle", "format": "project"}},
          {"id": "gradle-build", "name": "Gradle Config", "tags": {"build": "gradle", "format": "build"}},
          {"id": "maven-project", "name": "Maven", "tags": {"build": "maven", "format": "project"}}
        ]},
        "packaging": {"type": "single-select", "default": "jar", "values": [{"id": "jar", "name": "Jar"}]},
        "javaVersion": {"type": "single-select", "default": "21", "values": [{"id": "25", "name": "25"}, {"id": "21", "name": "21"}]},
        "language": {"type": "single-select", "default": "java", "values": [{"id": "java", "name": "Java"}, {"id": "kotlin", "name": "Kotlin"}, {"id": "scala", "name": "Scala"}]},
        "bootVersion": {"type": "single-select", "default": "4.1.1", "values": [{"id": "4.2.0-M2", "name": "4.2.0 (M2)"}, {"id": "4.1.1", "name": "4.1.1"}]},
        "groupId": {"type": "text", "default": "com.example"},
        "artifactId": {"type": "text", "default": "demo"},
        "packageName": {"type": "text", "default": "com.example.demo"}
      }
    """.trimIndent()
  }
}
