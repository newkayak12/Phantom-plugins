// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests

/**
 * Reads the published versions of a library from Maven Central's `maven-metadata.xml`. Called only from
 * [CheckNewerVersionsAction], so nothing reaches the network unless the user runs that action.
 */
object MavenCentral {
  private const val BASE_URL = "https://repo1.maven.org/maven2/"
  private val VERSION = Regex("""<version>\s*([^<\s]+)\s*</version>""")

  fun metadataUrl(group: String, artifact: String): String = BASE_URL + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml"

  fun versions(metadata: String): List<String> = VERSION.findAll(metadata).map { it.groupValues[1] }.toList()

  /** The versions of `group:artifact`, or null when Maven Central does not have it (a library from another repository). */
  fun fetchVersions(group: String, artifact: String, indicator: ProgressIndicator): List<String>? =
    try {
      versions(HttpRequests.request(metadataUrl(group, artifact)).productNameAsUserAgent().readString(indicator))
    } catch (e: HttpRequests.HttpStatusException) {
      if (e.statusCode == HTTP_NOT_FOUND) null else throw e
    }

  private const val HTTP_NOT_FOUND = 404
}
