// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.initializr

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.ide.starters.remote.SERVER_ARTIFACT_KEY
import com.intellij.ide.starters.remote.SERVER_CONFIGURATION_FILE_FORMAT_TYPES
import com.intellij.ide.starters.remote.SERVER_GROUP_KEY
import com.intellij.ide.starters.remote.SERVER_LANGUAGES
import com.intellij.ide.starters.remote.SERVER_LANGUAGE_LEVELS_KEY
import com.intellij.ide.starters.remote.SERVER_LANGUAGE_LEVEL_KEY
import com.intellij.ide.starters.remote.SERVER_PACKAGE_NAME_KEY
import com.intellij.ide.starters.remote.SERVER_PACKAGING_TYPES
import com.intellij.ide.starters.remote.SERVER_PROJECT_TYPES
import com.intellij.ide.starters.remote.SERVER_VERSION_KEY
import com.intellij.ide.starters.remote.WebStarterDependency
import com.intellij.ide.starters.remote.WebStarterDependencyCategory
import com.intellij.ide.starters.remote.WebStarterFrameworkVersion
import com.intellij.ide.starters.remote.WebStarterServerOptions
import com.intellij.ide.starters.shared.GROOVY_STARTER_LANGUAGE
import com.intellij.ide.starters.shared.JAVA_STARTER_LANGUAGE
import com.intellij.ide.starters.shared.KOTLIN_STARTER_LANGUAGE
import com.intellij.ide.starters.shared.LibraryLink
import com.intellij.ide.starters.shared.LibraryLinkType
import com.intellij.ide.starters.shared.StarterAppPackaging
import com.intellij.ide.starters.shared.StarterConfigFileFormat
import com.intellij.ide.starters.shared.StarterLanguage
import com.intellij.ide.starters.shared.StarterLanguageLevel
import com.intellij.ide.starters.shared.StarterProjectType

/** A starter dependency with the Spring Boot versions it supports, when the metadata limits them. */
internal class InitializrDependency(
  id: String, title: String, description: String?, links: List<LibraryLink>,
  val versionRange: BootVersionRange?,
) : WebStarterDependency(id, title, description, links)

/**
 * Reads the client metadata that start.spring.io (or a compatible Initializr server) serves for
 * `Accept: application/vnd.initializr.v2.2+json` into the options the web starter wizard shows.
 */
internal object InitializrMetadata {
  const val ACCEPT = "application/vnd.initializr.v2.2+json"

  val KNOWN_LANGUAGES: List<StarterLanguage> = listOf(JAVA_STARTER_LANGUAGE, KOTLIN_STARTER_LANGUAGE, GROOVY_STARTER_LANGUAGE)

  fun parse(root: JsonNode): WebStarterServerOptions {
    val defaultBoot = root.path("bootVersion").path("default").asText("")
    val versions = root.path("bootVersion").path("values").map {
      val id = it.path("id").asText()
      WebStarterFrameworkVersion(id, it.path("name").asText(id), id == defaultBoot)
    }
    val categories = root.path("dependencies").path("values").map { category ->
      val deps = category.path("values").map { dependency(it, defaultBoot) }
      WebStarterDependencyCategory(category.path("name").asText(), deps)
    }.filter { it.dependencies.isNotEmpty() }

    val options = WebStarterServerOptions(versions, categories)
    // Only full project archives: "maven-build" / "gradle-build" return a lone build file, which is not a project
    root.path("type").path("values")
      .filter { it.path("tags").path("format").asText("project") == "project" }
      .map { StarterProjectType(it.path("id").asText(), it.path("name").asText()) }
      .takeIf { it.isNotEmpty() }?.let { options.putUserData(SERVER_PROJECT_TYPES, it) }
    root.path("language").path("values").mapNotNull { v -> KNOWN_LANGUAGES.find { it.id == v.path("id").asText() } }
      .takeIf { it.isNotEmpty() }?.let { options.putUserData(SERVER_LANGUAGES, it) }
    root.path("packaging").path("values").map { StarterAppPackaging(it.path("id").asText(), it.path("name").asText()) }
      .takeIf { it.isNotEmpty() }?.let { options.putUserData(SERVER_PACKAGING_TYPES, it) }
    root.path("configurationFileFormat").path("values").map { StarterConfigFileFormat(it.path("id").asText(), it.path("name").asText()) }
      .takeIf { it.isNotEmpty() }?.let { options.putUserData(SERVER_CONFIGURATION_FILE_FORMAT_TYPES, it) }

    val levels = root.path("javaVersion").path("values").map { javaLevel(it.path("id").asText(), it.path("name").asText()) }
    if (levels.isNotEmpty()) {
      options.putUserData(SERVER_LANGUAGE_LEVELS_KEY, levels)
      val defaultLevel = root.path("javaVersion").path("default").asText("")
      levels.find { it.id == defaultLevel }?.let { options.putUserData(SERVER_LANGUAGE_LEVEL_KEY, it) }
    }
    text(root, "groupId")?.let { options.putUserData(SERVER_GROUP_KEY, it) }
    text(root, "artifactId")?.let { options.putUserData(SERVER_ARTIFACT_KEY, it) }
    text(root, "version")?.let { options.putUserData(SERVER_VERSION_KEY, it) }
    text(root, "packageName")?.let { options.putUserData(SERVER_PACKAGE_NAME_KEY, it) }
    return options
  }

  fun javaLevel(id: String, title: String = id): StarterLanguageLevel = StarterLanguageLevel(id, title, id)

  private fun text(root: JsonNode, field: String): String? = root.path(field).path("default").asText("").ifEmpty { null }

  private fun dependency(node: JsonNode, defaultBoot: String): InitializrDependency {
    val links = node.path("_links")
    val libraryLinks = buildList {
      links(links.path("reference"), defaultBoot).forEach { add(LibraryLink(LibraryLinkType.REFERENCE, it.first, it.second)) }
      links(links.path("guide"), defaultBoot).forEach { add(LibraryLink(LibraryLinkType.GUIDE, it.first, it.second)) }
      links(links.path("home"), defaultBoot).forEach { add(LibraryLink(LibraryLinkType.WEBSITE, it.first, it.second)) }
    }
    return InitializrDependency(
      id = node.path("id").asText(),
      title = node.path("name").asText(),
      description = node.path("description").asText("").ifEmpty { null },
      links = libraryLinks,
      versionRange = node.path("versionRange").asText("").ifEmpty { null }?.let(BootVersionRange::parse),
    )
  }

  /** A link entry is one `{href, title}` object or an array of them; templated hrefs take the default Boot version. */
  private fun links(node: JsonNode, bootVersion: String): List<Pair<String, String?>> {
    val entries = if (node.isArray) node.toList() else if (node.isObject) listOf(node) else return emptyList()
    return entries.mapNotNull { entry ->
      val href = entry.path("href").asText("").ifEmpty { return@mapNotNull null }.replace("{bootVersion}", bootVersion)
      if ('{' in href) null else href to entry.path("title").asText("").ifEmpty { null }
    }
  }
}
