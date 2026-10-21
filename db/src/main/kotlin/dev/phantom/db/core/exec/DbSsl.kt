// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbSslMode
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry

/** The SSL settings of the SSH/SSL tab in each driver's own words, from the `sslProperties` map in `drivers.xml`. */
object DbSsl {
  const val CA: String = "ca"
  const val CERT: String = "cert"
  const val KEY: String = "key"

  /** The definition's map; an edited built-in saved before the map existed takes the shipped one. */
  fun mapOf(definition: DbDriverDefinition): Map<String, String> =
    definition.sslProperties.ifEmpty { DbDriverRegistry.builtIns.firstOrNull { it.id == definition.id }?.sslProperties.orEmpty() }

  /** False: the driver has no SSL settings here (Oracle, SQLite, a driver of the user's own); the Properties table does it. */
  fun supports(definition: DbDriverDefinition?): Boolean = definition != null && mapOf(definition).isNotEmpty()

  /** Whether the driver takes a file of [kind] ([CA], [CERT], [KEY]). */
  fun takesFile(definition: DbDriverDefinition?, kind: String): Boolean = definition != null && mapOf(definition)[kind] != null

  /** Properties for [dataSource]'s SSL settings; none when SSL is off or the driver has no mapping. */
  fun properties(definition: DbDriverDefinition, dataSource: DbDataSource): Map<String, String> {
    if (!dataSource.sslEnabled) return emptyMap()
    val map = mapOf(definition)
    if (map.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    fun apply(key: String, file: String? = null) {
      val spec = map[key] ?: return
      for (pair in spec.split(';')) {
        val name = pair.substringBefore('=').trim()
        if (name.isEmpty()) continue
        result[name] = pair.substringAfter('=', "").replace("{file}", file.orEmpty())
      }
    }
    apply("enable")
    apply(modeKey(dataSource.sslMode))
    dataSource.sslCaFile.trim().takeIf { it.isNotEmpty() }?.let { apply(CA, it) }
    dataSource.sslCertFile.trim().takeIf { it.isNotEmpty() }?.let { apply(CERT, it) }
    dataSource.sslKeyFile.trim().takeIf { it.isNotEmpty() }?.let { apply(KEY, it) }
    return result
  }

  fun modeKey(mode: DbSslMode): String = when (mode) {
    DbSslMode.REQUIRE -> "require"
    DbSslMode.VERIFY_CA -> "verify-ca"
    DbSslMode.VERIFY_FULL -> "verify-full"
  }
}
