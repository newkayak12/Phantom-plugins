// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.drivers

import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.Dbms

/** A JDBC driver: how to recognize its URLs, where to get its jars and how to connect with it. Built-ins ship in `drivers.xml`. */
@Tag("driver")
class DbDriverDefinition {
  var id: String = ""
  var name: String = ""
  var builtIn: Boolean = false
  /** Key of the vendor icon, resolved by `PhantomDbIcons.forKey`; empty: the generic database icon. */
  var icon: String = ""
  var driverClass: String = ""
  /** Placeholders: `{host}` `{port}` `{database}` `{region}` `{file}`. */
  var urlTemplate: String = ""
  /** What `{port}` starts as in the data source dialog. */
  var defaultPort: Int? = null
  /** URL prefixes this driver handles, e.g. `jdbc:aws-wrapper:mysql:`. */
  @XCollection(style = XCollection.Style.v2)
  var urlPrefixes: MutableList<String> = ArrayList()
  /** `group:artifact` or `group:artifact:version`. No version means the latest one. */
  @XCollection(style = XCollection.Style.v2)
  var artifacts: MutableList<String> = ArrayList()
  /** True: [artifacts] are alternative names of one driver jar (newest first), not required roots; one jar is enough. */
  var artifactsAreAlternatives: Boolean = false
  /** Jars or directories chosen by hand. */
  @XCollection(style = XCollection.Style.v2)
  var files: MutableList<String> = ArrayList()
  @MapAnnotation
  var defaultProperties: MutableMap<String, String> = LinkedHashMap()
  /** Applied when the data source authenticates with AWS IAM. Values may use `{profile}` and `{region}`. */
  @MapAnnotation
  var iamProperties: MutableMap<String, String> = LinkedHashMap()
  /** URL prefix rewrite applied when the data source authenticates with AWS IAM. */
  var iamUrlFrom: String? = null
  var iamUrlTo: String? = null
  /**
   * How the data source's SSL settings become this driver's properties. Keys: `enable` (always when SSL is on), the modes
   * `require`, `verify-ca`, `verify-full`, and the files `ca`, `cert`, `key`. Values: `name=value` pairs split by `;`, where
   * `{file}` stands for the chosen file. Empty: the driver has no SSL settings here; use the Properties table.
   */
  @MapAnnotation
  var sslProperties: MutableMap<String, String> = LinkedHashMap()
  var dialect: Dbms = Dbms.GENERIC
  /** Pinned after a download. */
  var resolvedVersion: String? = null
  /** Absolute jar paths after a download. */
  @XCollection(style = XCollection.Style.v2)
  var resolvedJars: MutableList<String> = ArrayList()

  fun copy(): DbDriverDefinition = DbDriverDefinition().also {
    it.id = id; it.name = name; it.builtIn = builtIn; it.icon = icon; it.driverClass = driverClass; it.urlTemplate = urlTemplate; it.defaultPort = defaultPort
    it.urlPrefixes = ArrayList(urlPrefixes); it.artifacts = ArrayList(artifacts); it.artifactsAreAlternatives = artifactsAreAlternatives; it.files = ArrayList(files)
    it.defaultProperties = LinkedHashMap(defaultProperties); it.iamProperties = LinkedHashMap(iamProperties)
    it.iamUrlFrom = iamUrlFrom; it.iamUrlTo = iamUrlTo; it.dialect = dialect; it.sslProperties = LinkedHashMap(sslProperties)
    it.resolvedVersion = resolvedVersion; it.resolvedJars = ArrayList(resolvedJars)
  }

  override fun toString(): String = name
}
