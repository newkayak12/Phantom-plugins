// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
  /** Through an SSH tunnel the URL names 127.0.0.1: the property, if the driver has one, that checks the certificate against the real host. */
  const val TUNNEL_HOST: String = "tunnel-host"

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

  /** Whether [kind]'s file is a Java keystore (JKS/PKCS12) for this driver, not a PEM file (MySQL Connector/J, MariaDB's client cert). */
  fun isKeystore(definition: DbDriverDefinition?, kind: String): Boolean =
    definition != null && mapOf(definition)[kind]?.contains("keystore", ignoreCase = true) == true

  /**
   * Host name verification (verify full) compares the certificate with the URL's host, which a tunnel makes 127.0.0.1:
   * the properties that name the real [host] instead; empty when the driver has none (then verification fails).
   */
  fun tunnelProperties(definition: DbDriverDefinition, dataSource: DbDataSource, host: String): Map<String, String> {
    if (!dataSource.sslEnabled || dataSource.sslMode != DbSslMode.VERIFY_FULL) return emptyMap()
    val spec = mapOf(definition)[TUNNEL_HOST] ?: return emptyMap()
    return spec.split(';').mapNotNull { p -> p.substringBefore('=').trim().takeIf { it.isNotEmpty() }?.let { it to p.substringAfter('=', "").replace("{host}", host) } }.toMap()
  }

  /** True when verify full cannot work through the tunnel: the driver has no way to name the real host. */
  fun verifyFullBreaksWithTunnel(definition: DbDriverDefinition?, dataSource: DbDataSource): Boolean =
    dataSource.sshEnabled && dataSource.sslEnabled && dataSource.sslMode == DbSslMode.VERIFY_FULL &&
    definition != null && supports(definition) && mapOf(definition)[TUNNEL_HOST] == null

  fun modeKey(mode: DbSslMode): String = when (mode) {
    DbSslMode.REQUIRE -> "require"
    DbSslMode.VERIFY_CA -> "verify-ca"
    DbSslMode.VERIFY_FULL -> "verify-full"
  }
}
