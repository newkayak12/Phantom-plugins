// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.version.catalog

/** A version string in the catalog; [start] and [end] are the offsets of the text between the quotes. */
data class VersionLiteral(val start: Int, val end: Int, val value: String)

/** A `[libraries]` entry with either its own [version] or a [versionRef] into `[versions]`. */
data class CatalogLibrary(
  val alias: String,
  val group: String,
  val artifact: String,
  val version: VersionLiteral?,
  val versionRef: String?,
) {
  val coordinate: String get() = "$group:$artifact"
}

class VersionCatalog(val versions: Map<String, VersionLiteral>, val libraries: List<CatalogLibrary>)

/**
 * Reads the `[versions]` and `[libraries]` tables of a Gradle version catalog from its text. TOML keeps an inline table on
 * one line, so a line-by-line reading covers every catalog form Gradle accepts. Rich versions (`strictly`, `require`) are
 * not plain strings and are left out, since there is no single version to replace.
 */
object VersionCatalogParser {
  private val SECTION = Regex("""^\s*\[\s*([A-Za-z0-9_.-]+)\s*]\s*(#.*)?$""")
  private val ENTRY = Regex("""^\s*(["']?)([A-Za-z0-9_.-]+)\1\s*=\s*""")
  private val STRING = Regex("""^(["'])([^"']*)\1""")
  private val TABLE_FIELD = Regex("""([A-Za-z_][A-Za-z0-9_.]*)\s*=\s*(["'])([^"']*)\2""")

  fun parse(text: String): VersionCatalog {
    val versions = LinkedHashMap<String, VersionLiteral>()
    val libraries = ArrayList<CatalogLibrary>()
    var section = ""
    var lineStart = 0
    for (line in text.split('\n')) {
      val sectionMatch = SECTION.find(line)
      val entry = ENTRY.find(line)
      if (sectionMatch != null) {
        section = sectionMatch.groupValues[1]
      } else if (entry != null) {
        val key = entry.groupValues[2]
        val valueStart = entry.range.last + 1
        when (section) {
          "versions" -> stringAt(line, valueStart, lineStart)?.let { versions[key] = it }
          "libraries" -> library(key, line, valueStart, lineStart)?.let { libraries += it }
        }
      }
      lineStart += line.length + 1
    }
    return VersionCatalog(versions, libraries)
  }

  private fun stringAt(line: String, from: Int, lineStart: Int): VersionLiteral? {
    val match = STRING.find(line.substring(from)) ?: return null
    val start = lineStart + from + 1
    return VersionLiteral(start, start + match.groupValues[2].length, match.groupValues[2])
  }

  private fun library(alias: String, line: String, valueStart: Int, lineStart: Int): CatalogLibrary? {
    val notation = stringAt(line, valueStart, lineStart)
    if (notation != null) {
      // "group:artifact:version"; a notation without a version has nothing to update.
      val parts = notation.value.split(':')
      if (parts.size != 3) return null
      val versionStart = notation.end - parts[2].length
      return CatalogLibrary(alias, parts[0], parts[1], VersionLiteral(versionStart, notation.end, parts[2]), null)
    }
    if (!line.substring(valueStart).trimStart().startsWith("{")) return null
    val fields = HashMap<String, VersionLiteral>()
    for (field in TABLE_FIELD.findAll(line, valueStart)) {
      val start = lineStart + field.groups[3]!!.range.first
      fields[field.groupValues[1]] = VersionLiteral(start, start + field.groupValues[3].length, field.groupValues[3])
    }
    val module = fields["module"]?.value?.split(':')
    val group = if (module != null) module.getOrNull(0) else fields["group"]?.value
    val artifact = if (module != null) module.getOrNull(1) else fields["name"]?.value
    if (group.isNullOrEmpty() || artifact.isNullOrEmpty()) return null
    // `version.ref = "x"` and `version = { ref = "x" }` name the same thing.
    val ref = (fields["version.ref"] ?: fields["ref"])?.value
    return CatalogLibrary(alias, group, artifact, fields["version"], ref)
  }
}
