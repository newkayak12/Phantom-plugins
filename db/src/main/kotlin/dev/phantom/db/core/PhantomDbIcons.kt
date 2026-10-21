// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.icons.AllIcons
import dev.phantom.db.core.drivers.DbDriverDefinition
import javax.swing.Icon

/** Vendor icons, looked up by the key stored in [DbDriverDefinition.icon]. Resolved lazily; nothing loads at startup. */
object PhantomDbIcons {
  val GENERIC: Icon get() = AllIcons.Nodes.DataTables

  /** Platform vendor icons by key; the keys a driver can pick. */
  private val platform: Map<String, () -> Icon> = linkedMapOf(
    "mysql" to { AllIcons.Providers.Mysql },
    "mariadb" to { AllIcons.Providers.Mariadb },
    "postgresql" to { AllIcons.Providers.Postgresql },
    "h2" to { AllIcons.Providers.H2 },
    "oracle" to { AllIcons.Providers.Oracle },
    "sqlserver" to { AllIcons.Providers.SqlServer },
    "sqlite" to { AllIcons.Providers.Sqlite },
    "clickhouse" to { AllIcons.Providers.ClickHouse },
    "snowflake" to { AllIcons.Providers.Snowflake },
    "redshift" to { AllIcons.Providers.Redshift },
    "athena" to { AllIcons.Providers.Athena },
    "aws" to { AllIcons.Providers.AmazonWebServices },
  )

  /** Names for the icon combo of the Drivers dialog. */
  private val names = mapOf("mysql" to "MySQL", "mariadb" to "MariaDB", "postgresql" to "PostgreSQL", "h2" to "H2", "oracle" to "Oracle",
                            "sqlserver" to "SQL Server", "sqlite" to "SQLite", "clickhouse" to "ClickHouse", "snowflake" to "Snowflake",
                            "redshift" to "Redshift", "athena" to "Athena", "aws" to "AWS")

  fun displayName(key: String): String = names[key] ?: "Generic"

  /** Keys offered in the Drivers dialog; "" (generic) is not among them. */
  val keys: List<String> get() = platform.keys.toList()

  fun forKey(key: String): Icon = platform[key]?.invoke() ?: GENERIC

  fun forDriver(definition: DbDriverDefinition?): Icon {
    if (definition == null) return GENERIC
    return if (definition.icon.isNotEmpty()) forKey(definition.icon) else definition.dialect.icon
  }

  fun forDataSource(ds: DbDataSource): Icon = ds.driver()?.let(::forDriver) ?: ds.dialect().icon
}
