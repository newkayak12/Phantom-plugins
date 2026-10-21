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
import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.db.core.drivers.ArtifactFetcher
import dev.phantom.db.core.drivers.DbDriverDefinition
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.drivers.MavenCoordinate
import dev.phantom.db.core.drivers.MavenResolver
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

/**
 * The bound driver, else the one matching the URL. Only a data source bound to no driver at all falls back to the
 * built-in for the saved [DbDataSource.dbms]; a driver id this IDE does not know is an error, not a guess.
 */
fun DbDataSource.driver(): DbDriverDefinition? {
  val registry = DbDriverRegistry.getInstance()
  registry.find(driverId)?.let { return it }
  return registry.forUrl(url) ?: if (driverId.isNullOrEmpty()) registry.builtInFor(dbms) else null
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
  enum class Origin { CHOSEN, PROJECT_LIBRARY, LOCAL_CACHE, DOWNLOADED }

  val description: String
    get() = when (origin) {
      Origin.CHOSEN -> jars.joinToString { it.name }
      Origin.PROJECT_LIBRARY -> PhantomDbBundle.message("db.driver.project", jars.first().name)
      Origin.LOCAL_CACHE -> PhantomDbBundle.message("db.driver.cache", jars.first().name)
      Origin.DOWNLOADED -> PhantomDbBundle.message("db.driver.downloaded", jars.first().name)
    }
}

/**
 * Finds a JDBC driver without downloading: jars chosen for the data source, then those chosen for its driver definition,
 * then the project's own libraries (a Spring Boot project that talks to MySQL already depends on the connector), then the
 * Gradle and Maven caches in the user's home, then the jars a previous download pinned.
 */
object DbDrivers {
  /** Blocking file lookups; call off the EDT. Project libraries need a read action, which this takes itself. */
  fun find(project: Project?, dataSource: DbDataSource): DbDriver? {
    if (dataSource.driverJars.isNotEmpty()) {
      return DbDriver(dataSource.driverJars.map { Path.of(it) }, null, DbDriver.Origin.CHOSEN)
    }
    val definition = dataSource.driver() ?: return null
    if (definition.files.isNotEmpty()) {
      return DbDriver(definition.files.flatMap { jarsIn(Path.of(it)) }, null, DbDriver.Origin.CHOSEN)
    }
    return project?.let { fromProjectLibraries(it, definition) }
           ?: fromLocalCaches(definition)
           ?: fromDownloads(definition)
  }

  fun fromProjectLibraries(project: Project, definition: DbDriverDefinition): DbDriver? {
    if (definition.artifacts.isEmpty()) return null
    val roots = com.intellij.openapi.application.runReadAction {
      OrderEnumerator.orderEntries(project).librariesOnly().recursively().classes().roots.toList()
    }
    val jars = roots.map { root -> VfsUtilCore.virtualToIoFile(JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root).toPath() }
    if (definition.artifactsAreAlternatives) {
      return newest(jars.mapNotNull { jar -> versionOf(jar.name, definition.artifacts)?.let { jar to it } }, DbDriver.Origin.PROJECT_LIBRARY)
    }
    val pinned = roots(definition)?.map { c ->
      val candidates = jars.mapNotNull { jar -> versionOf(jar.name, listOf("${c.group}:${c.artifact}"))?.let { jar to it } }.filter { c.version == null || it.second == c.version }
      val best = candidates.maxWithOrNull { a, b -> VersionComparatorUtil.compare(a.second, b.second) } ?: return null
      c.copy(version = best.second)
    } ?: return null
    val byName = jars.associateBy { it.name }
    return withDependencies(pinned, home(), DbDriver.Origin.PROJECT_LIBRARY) { byName[it] }
  }

  /** `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.jar` and `~/.m2/repository`. */
  fun fromLocalCaches(definition: DbDriverDefinition, home: Path = home()): DbDriver? {
    if (definition.artifacts.isEmpty()) return null
    if (definition.artifactsAreAlternatives) {
      return newest(definition.artifacts.flatMap { cached(home, it.substringBefore(':'), it.substringAfter(':').substringBefore(':')) },
                    DbDriver.Origin.LOCAL_CACHE)
    }
    val pinned = roots(definition)?.map { c ->
      val version = if (c.version != null) c.version.takeIf { localJar(home, c.group, c.artifact, it) != null }
                    else cached(home, c.group, c.artifact).maxWithOrNull { a, b -> VersionComparatorUtil.compare(a.second, b.second) }?.second
      c.copy(version = version ?: return null)
    } ?: return null
    return withDependencies(pinned, home, DbDriver.Origin.LOCAL_CACHE) { null }
  }

  /** The jars a download pinned, if they are all still there. */
  fun fromDownloads(definition: DbDriverDefinition): DbDriver? {
    if (definition.resolvedJars.isEmpty()) return null
    val jars = definition.resolvedJars.map { Path.of(it) }
    return if (jars.all { Files.isRegularFile(it) }) DbDriver(jars, definition.resolvedVersion, DbDriver.Origin.DOWNLOADED) else null
  }

  /** True when a project library looks like a JDBC driver of a built-in preset. Names only, no file access. */
  fun projectHasDriverLibrary(project: Project): Boolean {
    val names = LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries.mapNotNull { it.name }
    return names.any { isDriverLibraryName(it) }
  }

  /** True when [name] (like `Gradle: group:artifact:1.0`) contains `group:artifact` of a built-in driver's artifacts. */
  internal fun isDriverLibraryName(name: String): Boolean =
    DbDriverRegistry.builtIns.any { d -> d.artifacts.any { name.contains(it.split(':').take(2).joinToString(":")) } }

  internal fun versionOf(jarName: String, artifacts: List<String>): String? {
    for (coordinates in artifacts) {
      val artifact = coordinates.substringAfter(':').substringBefore(':')
      val prefix = "$artifact-"
      if (!jarName.startsWith(prefix) || !jarName.endsWith(".jar")) continue
      val version = jarName.removePrefix(prefix).removeSuffix(".jar")
      if (version.firstOrNull()?.isDigit() == true && !version.endsWith("-sources") && !version.endsWith("-javadoc")) return version
    }
    return null
  }

  /** Required roots; the first one pinned to the downloaded version unless it names its own. `null` if one is not a coordinate. */
  private fun roots(definition: DbDriverDefinition): List<MavenCoordinate>? {
    val roots = definition.artifacts.map { runCatching { MavenCoordinate.parse(it) }.getOrNull() ?: return null }
    val pin = definition.resolvedVersion ?: return roots
    return roots.mapIndexed { i, c -> if (i == 0 && c.version == null) c.copy(version = pin) else c }
  }

  private fun home(): Path = Path.of(System.getProperty("user.home"))

  /**
   * Resolves the transitive dependencies of [roots] from the POMs in the local caches; each jar comes from [preferred]
   * (by file name), else from the caches. `null` when a POM or a jar is missing: the caller skips this step.
   */
  private fun withDependencies(roots: List<MavenCoordinate>, home: Path, origin: DbDriver.Origin, preferred: (String) -> Path?): DbDriver? {
    val m2 = home.resolve(".m2/repository").toUri().toString()
    val resolver = MavenResolver(listOf(m2, GRADLE_CACHE_BASE), ArtifactFetcher { url -> fetchLocal(home, m2, url) })
    val resolved = try {
      resolver.resolve(roots)
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (_: Exception) {
      return null
    }
    val jars = resolved.map { a -> preferred("${a.artifact}-${a.version}.jar") ?: localJar(home, a.group, a.artifact, a.version) ?: return null }
    return DbDriver(jars, roots.first().version, origin)
  }

  private fun fetchLocal(home: Path, m2: String, url: String): ByteArray? {
    val file = when {
      url.startsWith(m2) -> runCatching { Path.of(java.net.URI(url)) }.getOrNull()
      url.startsWith(GRADLE_CACHE_BASE) -> {
        // <group path>/<artifact>/<version>/<file>; maven-metadata.xml has no place in the Gradle cache
        val parts = url.removePrefix(GRADLE_CACHE_BASE).split('/')
        if (parts.size < 4) null
        else {
          val (artifact, version, name) = parts.takeLast(3)
          val dir = home.resolve("$GRADLE_FILES/${parts.dropLast(3).joinToString(".")}/$artifact/$version")
          children(dir).map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) }
        }
      }
      else -> null
    }
    return file?.takeIf { Files.isRegularFile(it) }?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
  }

  /** Every cached `<artifact>-<version>.jar` of [group]:[artifact], with its version. */
  private fun cached(home: Path, group: String, artifact: String): List<Pair<Path, String>> {
    val candidates = ArrayList<Pair<Path, String>>()
    for (versionDir in children(home.resolve("$GRADLE_FILES/$group/$artifact"))) {
      for (hashDir in children(versionDir)) {
        val jar = hashDir.resolve("$artifact-${versionDir.name}.jar")
        if (Files.isRegularFile(jar)) candidates.add(jar to versionDir.name)
      }
    }
    for (versionDir in children(home.resolve(".m2/repository/${group.replace('.', '/')}/$artifact"))) {
      val jar = versionDir.resolve("$artifact-${versionDir.name}.jar")
      if (Files.isRegularFile(jar)) candidates.add(jar to versionDir.name)
    }
    return candidates
  }

  private fun localJar(home: Path, group: String, artifact: String, version: String): Path? {
    val name = "$artifact-$version.jar"
    home.resolve(".m2/repository/${group.replace('.', '/')}/$artifact/$version/$name").let { if (Files.isRegularFile(it)) return it }
    return children(home.resolve("$GRADLE_FILES/$group/$artifact/$version")).map { it.resolve(name) }.firstOrNull { Files.isRegularFile(it) }
  }

  /** A chosen directory stands for the jars in it. */
  private fun jarsIn(path: Path): List<Path> =
    if (path.isDirectory()) runCatching { path.listDirectoryEntries("*.jar").sorted() }.getOrDefault(emptyList()) else listOf(path)

  private fun newest(candidates: List<Pair<Path, String>>, origin: DbDriver.Origin): DbDriver? {
    val best = candidates.maxWithOrNull { a, b -> VersionComparatorUtil.compare(a.second, b.second) } ?: return null
    return DbDriver(listOf(best.first), best.second, origin)
  }

  private fun children(dir: Path): List<Path> =
    if (dir.isDirectory()) runCatching { dir.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList()) else emptyList()

  private const val GRADLE_FILES = ".gradle/caches/modules-2/files-2.1"
  /** Not a real URL: [fetchLocal] maps it onto the Gradle cache layout. */
  private const val GRADLE_CACHE_BASE = "gradle-cache:/"
}
