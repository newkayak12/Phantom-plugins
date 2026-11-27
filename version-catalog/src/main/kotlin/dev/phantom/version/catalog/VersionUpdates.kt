// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

import com.intellij.util.text.VersionComparatorUtil

/** A version literal to replace: a library's own version, or a `[versions]` entry when [isVersionRef]. */
data class VersionUpdate(val name: String, val isVersionRef: Boolean, val literal: VersionLiteral, val newVersion: String)

object VersionUpdates {
  private val UNSTABLE = Regex("""(?i)(alpha|beta|rc|cr|milestone|snapshot|preview|eap|dev)|[.-]m\d+$""")
  private val DYNAMIC = Regex("""[+\[\](),$]|^latest\.""")

  fun isStable(version: String): Boolean = !UNSTABLE.containsMatchIn(version)

  /** The newest of [available]; pre-releases count only when [current] is a pre-release itself. */
  fun newest(available: List<String>, current: String): String? {
    val allowUnstable = !isStable(current)
    return available.filter { allowUnstable || isStable(it) }.maxWithOrNull(VersionComparatorUtil::compare)
  }

  /**
   * The updates for [catalog], given the versions Maven Central lists per coordinate. A `[versions]` entry shared by several
   * libraries moves only as far as the oldest of their newest versions, so every library using it exists in that version.
   */
  fun compute(catalog: VersionCatalog, available: Map<String, List<String>>): List<VersionUpdate> {
    val own = catalog.libraries.mapNotNull { library ->
      library.version?.let { update(library.alias, false, it, listOfNotNull(available[library.coordinate])) }
    }
    val shared = catalog.libraries.filter { it.version == null && it.versionRef != null }.groupBy { it.versionRef!! }
      .mapNotNull { (ref, users) ->
        catalog.versions[ref]?.let { literal -> update(ref, true, literal, users.mapNotNull { available[it.coordinate] }) }
      }
    return (own + shared).sortedBy { it.literal.start }
  }

  private fun update(name: String, isVersionRef: Boolean, literal: VersionLiteral, versionLists: List<List<String>>): VersionUpdate? {
    val newest = versionLists.mapNotNull { newest(it, literal.value) }.minWithOrNull(VersionComparatorUtil::compare) ?: return null
    return if (isNewer(newest, literal.value)) VersionUpdate(name, isVersionRef, literal, newest) else null
  }

  /** [text] with each update's literal replaced, from the end so earlier offsets stay valid. */
  fun apply(text: String, updates: List<VersionUpdate>): String {
    val result = StringBuilder(text)
    for (update in updates.sortedByDescending { it.literal.start }) {
      result.replace(update.literal.start, update.literal.end, update.newVersion)
    }
    return result.toString()
  }

  private fun isNewer(candidate: String, current: String): Boolean =
    !DYNAMIC.containsMatchIn(current) && VersionComparatorUtil.compare(candidate, current) > 0
}
