// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.icons.AllIcons
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.text.VersionComparatorUtil
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Supported databases. One JDBC driver jar each, with Maven coordinates used to find it in project libraries and caches. */
enum class Dbms(
  val displayName: String,
  val urlPrefix: String,
  val urlTemplate: String,
  val driverClass: String,
  /** `group:artifact`, newest naming first. */
  val artifacts: List<String>,
) {
  MYSQL("MySQL", "jdbc:mysql:", "jdbc:mysql://localhost:3306/", "com.mysql.cj.jdbc.Driver",
        listOf("com.mysql:mysql-connector-j", "mysql:mysql-connector-java")),
  MARIADB("MariaDB", "jdbc:mariadb:", "jdbc:mariadb://localhost:3306/", "org.mariadb.jdbc.Driver",
          listOf("org.mariadb.jdbc:mariadb-java-client")),
  POSTGRESQL("PostgreSQL", "jdbc:postgresql:", "jdbc:postgresql://localhost:5432/postgres", "org.postgresql.Driver",
             listOf("org.postgresql:postgresql")),
  H2("H2", "jdbc:h2:", "jdbc:h2:mem:test", "org.h2.Driver", listOf("com.h2database:h2")),
  /** Any other JDBC database: connects and browses, but has no SQL dialect knowledge. Never detected from a URL. */
  GENERIC("Other", "", "jdbc:", "", emptyList());

  val icon: Icon
    get() = when (this) {
      MYSQL -> AllIcons.Providers.Mysql
      MARIADB -> AllIcons.Providers.Mariadb
      POSTGRESQL -> AllIcons.Providers.Postgresql
      H2 -> AllIcons.Providers.H2
      GENERIC -> AllIcons.Nodes.DataTables
    }

  /** False for [GENERIC]: no EXPLAIN, DDL, schema changes or dialect checks. */
  val hasDialect: Boolean get() = this != GENERIC

  /** MySQL keeps databases in catalogs; the others in schemas. */
  val usesCatalogs: Boolean get() = this == MYSQL || this == MARIADB

  companion object {
    fun forUrl(url: String): Dbms? {
      val u = url.trim().removePrefix("jdbc:p6spy:").let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
      return entries.firstOrNull { it.hasDialect && u.startsWith(it.urlPrefix, ignoreCase = true) }
    }
  }
}

/** Saved in `.idea/phantomDataSources.xml`. The password is not here: it lives in the IDE password safe. */
@Tag("data-source")
class DbDataSource {
  var id: String = UUID.randomUUID().toString()
  var name: String = ""
  var dbms: Dbms = Dbms.MYSQL
  var url: String = ""
  var user: String = ""
  var readOnly: Boolean = false
  /** Changes stay in an open transaction until committed from the Database Changes tool window. */
  var manualCommit: Boolean = false
  /** Driver jars chosen by hand. Empty: find the driver in project libraries, then in the Gradle and Maven caches. */
  @XCollection(style = XCollection.Style.v2)
  var driverJars: MutableList<String> = ArrayList()
  var maxRows: Int = 500
  var queryTimeoutSeconds: Int = 60
  /** The [DbDriverDefinition] this data source connects with. `null` in state written before drivers existed. */
  var driverId: String? = null
  /** JDBC connection properties; override the driver's defaults. */
  @MapAnnotation
  var properties: MutableMap<String, String> = LinkedHashMap()
  var authKind: DbAuthKind = DbAuthKind.PASSWORD
  var awsProfile: String = ""
  var awsRegion: String = ""

  fun copy(): DbDataSource = DbDataSource().also {
    it.id = id; it.name = name; it.dbms = dbms; it.url = url; it.user = user; it.readOnly = readOnly; it.manualCommit = manualCommit
    it.driverJars = ArrayList(driverJars); it.maxRows = maxRows; it.queryTimeoutSeconds = queryTimeoutSeconds
    it.driverId = driverId; it.properties = LinkedHashMap(properties); it.authKind = authKind
    it.awsProfile = awsProfile; it.awsRegion = awsRegion
  }

  override fun toString(): String = name
}

enum class DbAuthKind { PASSWORD, NONE, AWS_IAM }

/** The bound driver, else the one matching the URL, else the built-in for the saved [DbDataSource.dbms]. */
fun DbDataSource.driver(): DbDriverDefinition? {
  val registry = DbDriverRegistry.getInstance()
  return registry.find(driverId) ?: registry.forUrl(url) ?: registry.builtInFor(dbms)
}

/** SQL dialect of this data source: that of its driver; [DbDataSource.dbms] is only the persisted copy. */
fun DbDataSource.dialect(): Dbms = driver()?.dialect ?: dbms

/** Project data sources. Loaded on first use (tool window, console, `.sql` editor banner), never at startup. */
@Service(Service.Level.PROJECT)
@State(name = "PhantomDataSources", storages = [Storage("phantomDataSources.xml")])
class DbDataSourceService : PersistentStateComponent<DbDataSourceService.State> {
  class State {
    @XCollection(style = XCollection.Style.v2)
    var dataSources: MutableList<DbDataSource> = ArrayList()
  }

  private var state = State()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  override fun getState(): State = state
  override fun loadState(state: State) {
    state.dataSources.filter { it.driverId == null }.forEach { ds ->
      DbDriverRegistry.getInstance().builtInFor(ds.dbms)?.let { ds.driverId = it.id }
    }
    this.state = state
  }

  val dataSources: List<DbDataSource> get() = state.dataSources.toList()

  fun find(id: String?): DbDataSource? = id?.let { i -> state.dataSources.firstOrNull { it.id == i } }

  /** Adds [dataSource] or replaces the saved one with the same id. A `null` [password] leaves the saved one as is. */
  fun save(dataSource: DbDataSource, password: CharArray?) {
    dataSource.dbms = dataSource.dialect()
    val i = state.dataSources.indexOfFirst { it.id == dataSource.id }
    if (i >= 0) state.dataSources[i] = dataSource else state.dataSources.add(dataSource)
    if (password != null) PasswordSafe.instance.set(credentials(dataSource.id), Credentials(dataSource.user, password))
    changed()
  }

  fun remove(id: String) {
    state.dataSources.removeIf { it.id == id }
    PasswordSafe.instance.set(credentials(id), null)
    changed()
  }

  /** Blocking: the password safe may ask the OS keychain. Call off the EDT. */
  fun password(id: String): String? = PasswordSafe.instance.getPassword(credentials(id))

  fun addListener(parent: Disposable, listener: () -> Unit) {
    listeners.add(listener)
    Disposer.register(parent) { listeners.remove(listener) }
  }

  private fun changed() = listeners.forEach { it() }

  companion object {
    fun getInstance(project: Project): DbDataSourceService = project.service()

    fun credentials(id: String): CredentialAttributes = CredentialAttributes(generateServiceName("Phantom DB", id))
  }
}

/** Where a driver was found. */
data class DbDriver(val jars: List<Path>, val version: String?, val origin: Origin) {
  enum class Origin { CHOSEN, PROJECT_LIBRARY, LOCAL_CACHE }

  val description: String
    get() = when (origin) {
      Origin.CHOSEN -> jars.joinToString { it.name }
      Origin.PROJECT_LIBRARY -> PhantomDbBundle.message("db.driver.project", jars.first().name)
      Origin.LOCAL_CACHE -> PhantomDbBundle.message("db.driver.cache", jars.first().name)
    }
}

/**
 * Finds a JDBC driver without downloading: jars chosen by hand, then the project's own libraries (a Spring Boot project
 * that talks to MySQL already depends on the connector), then the Gradle and Maven caches in the user's home.
 */
object DbDrivers {
  /** Blocking file lookups; call off the EDT. Project libraries need a read action, which this takes itself. */
  fun find(project: Project?, dataSource: DbDataSource): DbDriver? {
    if (dataSource.driverJars.isNotEmpty()) {
      return DbDriver(dataSource.driverJars.map { Path.of(it) }, null, DbDriver.Origin.CHOSEN)
    }
    return project?.let { fromProjectLibraries(it, dataSource.dbms) } ?: fromLocalCaches(dataSource.dbms)
  }

  fun fromProjectLibraries(project: Project, dbms: Dbms): DbDriver? {
    val roots = com.intellij.openapi.application.runReadAction {
      OrderEnumerator.orderEntries(project).librariesOnly().recursively().classes().roots.toList()
    }
    val candidates = roots.mapNotNull { root ->
      val jar = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root
      val version = versionOf(jar.name, dbms) ?: return@mapNotNull null
      VfsUtilCore.virtualToIoFile(jar).toPath() to version
    }
    return newest(candidates, DbDriver.Origin.PROJECT_LIBRARY)
  }

  /** `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.jar` and `~/.m2/repository`. */
  fun fromLocalCaches(dbms: Dbms, home: Path = Path.of(System.getProperty("user.home"))): DbDriver? {
    val candidates = ArrayList<Pair<Path, String>>()
    for (coordinates in dbms.artifacts) {
      val (group, artifact) = coordinates.split(':')
      val gradle = home.resolve(".gradle/caches/modules-2/files-2.1/$group/$artifact")
      for (versionDir in children(gradle)) {
        for (hashDir in children(versionDir)) {
          val jar = hashDir.resolve("$artifact-${versionDir.name}.jar")
          if (Files.isRegularFile(jar)) candidates.add(jar to versionDir.name)
        }
      }
      val maven = home.resolve(".m2/repository/${group.replace('.', '/')}/$artifact")
      for (versionDir in children(maven)) {
        val jar = versionDir.resolve("$artifact-${versionDir.name}.jar")
        if (Files.isRegularFile(jar)) candidates.add(jar to versionDir.name)
      }
    }
    return newest(candidates, DbDriver.Origin.LOCAL_CACHE)
  }

  /** True when a project library looks like a JDBC driver for one of [Dbms]. Names only, no file access. */
  fun projectHasDriverLibrary(project: Project): Boolean {
    val names = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries.mapNotNull { it.name }
    return names.any { name -> Dbms.entries.any { dbms -> dbms.artifacts.any { name.contains(it) } } }
  }

  internal fun versionOf(jarName: String, dbms: Dbms): String? {
    for (coordinates in dbms.artifacts) {
      val artifact = coordinates.substringAfter(':')
      val prefix = "$artifact-"
      if (!jarName.startsWith(prefix) || !jarName.endsWith(".jar")) continue
      val version = jarName.removePrefix(prefix).removeSuffix(".jar")
      if (version.firstOrNull()?.isDigit() == true && !version.endsWith("-sources") && !version.endsWith("-javadoc")) return version
    }
    return null
  }

  private fun newest(candidates: List<Pair<Path, String>>, origin: DbDriver.Origin): DbDriver? {
    val best = candidates.maxWithOrNull { a, b -> VersionComparatorUtil.compare(a.second, b.second) } ?: return null
    return DbDriver(listOf(best.first), best.second, origin)
  }

  private fun children(dir: Path): List<Path> =
    if (dir.isDirectory()) runCatching { dir.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList()) else emptyList()
}
