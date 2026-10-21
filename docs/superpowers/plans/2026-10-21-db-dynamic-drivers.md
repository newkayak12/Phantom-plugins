# db: Dynamic JDBC Drivers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make JDBC drivers data instead of code: user-defined drivers, Maven download with transitive dependencies, AWS/other presets, and a generic dialect so any driver can run consoles and browse schemas.

**Architecture:** Split today's `Dbms` enum into *dialect* (stays `Dbms`, gains `GENERIC`) and *driver* (new `DbDriverDefinition`, built-ins in an XML resource plus an application-level registry of user drivers/overrides). A network-free-testable Maven resolver feeds a download cache; `DbDrivers.find` becomes a lookup chain over the definition; `DbConnectionService.open` builds the connection from definition + data source properties + auth kind.

**Tech Stack:** Kotlin, IntelliJ Platform (`PersistentStateComponent`, `XmlSerializer`, `HttpRequests`, Kotlin UI DSL), JDK `javax.xml` for POM parsing, BasePlatformTestCase tests.

**Spec:** `docs/superpowers/specs/2026-10-21-db-dynamic-drivers-design.md`

## Global Constraints

- `phantom.db.core` depends only on `com.intellij.modules.lang` + grid. No Java-plugin APIs (`JarRepositoryManager` etc.) in core.
- Nothing runs at IDE startup; no network unless the user clicks Download / Test Connection / Check for updates.
- Connect/console/tree with a missing driver never downloads silently: show a notification with a "Download driver" action.
- `LATEST` resolves once at download time; the resolved version is stored and reused without online checks.
- Each driver keeps its own `URLClassLoader` (parent: platform loader), called directly, not through `DriverManager`.
- Passwords and AWS secrets only in `PasswordSafe`; never in `.idea/phantomDataSources.xml` or the app-level drivers file.
- Data sources keep writing `dbms` so 0.10.0 can read them.
- Built-in MySQL/MariaDB/PostgreSQL/H2 keep the single-jar fallback from project libraries and local caches (no download required for existing users). The "all transitive deps present" rule applies only to other drivers.
- Tests run without network (CI). Resolver/downloader tests use a local fake repository.
- Plugin version `0.11.0`.
- Test command: `./gradlew :db:test --tests '<class>'`.

## Review Focus

1. Existing `.idea/phantomDataSources.xml` (no `driverId`) opens and connects exactly as in 0.10.0 → Task 3 `testOldStateMigratesToBuiltInDriver`, Task 5 `testBuiltInKeepsSingleJarFallback`.
2. A committed data source pointing at a custom driver this IDE doesn't have → falls back by URL prefix, else clear error with a "Configure Drivers" link → Task 3 `testUnknownDriverIdFallsBackByUrl`, Task 6 `testUnknownDriverErrorMentionsDriver`.
3. POMs with parent + `${project.version}` + BOM import + exclusions + optional → correct jar set, no optional jars → Task 4 tests.
4. A GENERIC data source: console, grid, schema tree work; EXPLAIN / DDL / modify table / compare / grid edit are off (no MySQL-flavoured SQL sent to Redshift) → Task 1 and Task 6 tests.
5. Corrupt or partial download (checksum mismatch, cancel mid-way) leaves no jar that later lookups pick up → Task 5 `testChecksumMismatchLeavesNothing`.

---

### Task 1: `GENERIC` dialect

**Files:**
- Modify: `db/src/main/kotlin/dev/phantom/db/core/DataSources.kt` (enum `Dbms`)
- Modify every exhaustive `when` over `Dbms`: `exec/DbDdl.kt`, `exec/DbSchemaObjects.kt`, `exec/DbAlter.kt`, `exec/DbExplain.kt`, `exec/DbSchemaDiff.kt`, `exec/DbConnections.kt:168`, `SqlResolve.kt:177`, `SqlDialectInspections.kt`
- Modify action `update()`s for Explain, Show DDL, Modify Table, Compare With, grid editing (`DbGridEditing.kt`/`DbTableEdits.kt`)
- Test: `db/src/test/kotlin/dev/phantom/db/DbGenericDialectTest.kt`

**Interfaces:**
- Produces: `Dbms.GENERIC("Other", urlPrefix = "", urlTemplate = "jdbc:", driverClass = "", artifacts = emptyList())`, icon `AllIcons.Providers.DataSource` (or nearest existing icon); `val Dbms.hasDialect: Boolean` (false only for `GENERIC`); `DbExplain.sql(dbms, statement, analyze): String?` (null for `GENERIC`); `DbAlter`/`DbSchemaDiff` entry points throw `DbException` for `GENERIC` (callers are gated so this is unreachable from UI).
- `Dbms.forUrl` must never return `GENERIC` (empty prefix must not match everything).
- `GENERIC` quoting in `DbNames.quote`: ANSI double quotes. `usesCatalogs` false. Triggers query: none (empty list). DDL source: `null`.

- [ ] **Step 1: Write failing tests**

```kotlin
fun testGenericHasNoDialectFeatures() {
  assertFalse(Dbms.GENERIC.hasDialect)
  assertTrue(Dbms.entries.filter { it != Dbms.GENERIC }.all { it.hasDialect })
  assertNull(DbExplain.sql(Dbms.GENERIC, "SELECT 1", analyze = false))
  assertEquals("\"a b\"", DbNames.quote(Dbms.GENERIC, "a b"))
  assertNull(Dbms.forUrl("jdbc:redshift://x:5439/dev"))
  assertEquals(Dbms.MYSQL, Dbms.forUrl("jdbc:mysql://h/db"))
}
fun testDialectInspectionsSkipGeneric() {
  // a .sql file bound to a GENERIC data source gets no dialect-only warnings (reserved words, arity)
}
```
Second test: bind file to an H2 data source saved with `dbms = Dbms.GENERIC` (same pattern as `DbConsoleTest.h2()`), `myFixture.enableInspections(...)` for the dialect inspections, `CREATE TABLE \`order\` (id INT)` → `assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))`.

- [ ] **Step 2: Run** `./gradlew :db:test --tests 'dev.phantom.db.DbGenericDialectTest'` → FAIL (`GENERIC` unresolved).
- [ ] **Step 3: Add `GENERIC`, `hasDialect`; fix every `when` (compile errors list them); in each dialect-only action's `update()`, `isEnabledAndVisible = false` when the data source's `dbms.hasDialect` is false.**
- [ ] **Step 4: Run the new test and the full `./gradlew :db:test`** → all PASS.
- [ ] **Step 5: Commit** `db: GENERIC dialect for databases without SQL intelligence`

---

### Task 2: Driver definitions and registry

**Files:**
- Create: `db/src/main/kotlin/dev/phantom/db/core/drivers/DbDriverDefinition.kt`
- Create: `db/src/main/kotlin/dev/phantom/db/core/drivers/DbDriverRegistry.kt`
- Create: `db/src/main/resources/phantom/db/drivers.xml`
- Modify: `db/src/main/resources/phantom.db.core.xml` (register app service)
- Test: `db/src/test/kotlin/dev/phantom/db/DbDriverRegistryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Tag("driver") class DbDriverDefinition {
    var id: String; var name: String; var builtIn: Boolean
    var driverClass: String
    var urlTemplate: String              // placeholders {host} {port} {database} {region} {file}
    @XCollection var urlPrefixes: MutableList<String>   // e.g. "jdbc:aws-wrapper:mysql:"
    @XCollection var artifacts: MutableList<String>     // "group:artifact" or "group:artifact:version"; no version = LATEST
    @XCollection var files: MutableList<String>         // jars/dirs chosen by hand
    @MapAnnotation var defaultProperties: MutableMap<String, String>
    @MapAnnotation var iamProperties: MutableMap<String, String>  // values may use {profile} {region}
    var iamUrlFrom: String?; var iamUrlTo: String?      // URL prefix rewrite when auth = AWS_IAM
    var dialect: Dbms
    var resolvedVersion: String?                       // pinned after download
    @XCollection var resolvedJars: MutableList<String> // absolute paths after download
    fun copy(): DbDriverDefinition
  }
  @Service(Service.Level.APP) @State(name = "PhantomDbDrivers", storages = [Storage("phantomDbDrivers.xml")])
  class DbDriverRegistry : PersistentStateComponent<DbDriverRegistry.State> {
    val all: List<DbDriverDefinition>          // built-ins with user overrides applied, then user drivers
    fun find(id: String?): DbDriverDefinition?
    fun forUrl(url: String): DbDriverDefinition? // longest matching urlPrefix; strips "jdbc:p6spy:" like Dbms.forUrl
    fun builtInFor(dbms: Dbms): DbDriverDefinition?
    fun save(definition: DbDriverDefinition)    // built-in id → stored as override; else user driver
    fun remove(id: String)                      // user drivers only; built-in → resetToDefault
    fun resetToDefault(id: String)
    fun addListener(parent: Disposable, listener: () -> Unit)
    companion object { fun getInstance(): DbDriverRegistry; val builtIns: List<DbDriverDefinition> }
  }
  ```
- Built-ins in `drivers.xml` read with `XmlSerializer.deserialize` (lazily, first `getInstance()` use). Ids and values:

| id | name | class | urlTemplate | prefixes | artifacts | dialect |
|---|---|---|---|---|---|---|
| `mysql` | MySQL | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://{host}:3306/{database}` | `jdbc:mysql:` | `com.mysql:mysql-connector-j`, `mysql:mysql-connector-java` | MYSQL |
| `mariadb` | MariaDB | `org.mariadb.jdbc.Driver` | `jdbc:mariadb://{host}:3306/{database}` | `jdbc:mariadb:` | `org.mariadb.jdbc:mariadb-java-client` | MARIADB |
| `postgresql` | PostgreSQL | `org.postgresql.Driver` | `jdbc:postgresql://{host}:5432/{database}` | `jdbc:postgresql:` | `org.postgresql:postgresql` | POSTGRESQL |
| `h2` | H2 | `org.h2.Driver` | `jdbc:h2:mem:test` | `jdbc:h2:` | `com.h2database:h2` | H2 |
| `aws-wrapper-mysql` | AWS JDBC Wrapper (MySQL) | `software.amazon.jdbc.Driver` | `jdbc:aws-wrapper:mysql://{host}:3306/{database}` | `jdbc:aws-wrapper:mysql:` | `software.amazon.jdbc:aws-advanced-jdbc-wrapper`, `com.mysql:mysql-connector-j`, `software.amazon.awssdk:rds` | MYSQL |
| `aws-wrapper-postgresql` | AWS JDBC Wrapper (PostgreSQL) | same | `jdbc:aws-wrapper:postgresql://{host}:5432/{database}` | `jdbc:aws-wrapper:postgresql:` | wrapper, `org.postgresql:postgresql`, `software.amazon.awssdk:rds` | POSTGRESQL |
| `redshift` | Amazon Redshift | `com.amazon.redshift.jdbc.Driver` | `jdbc:redshift://{host}:5439/{database}` | `jdbc:redshift:` | `com.amazon.redshift:redshift-jdbc42` (+ SDK artifacts its POM marks optional that IAM needs — verify in Task 8) | GENERIC |
| `athena` | Amazon Athena | `com.amazon.athena.jdbc.AthenaDriver` | `jdbc:athena://Region={region};WorkGroup=primary` | `jdbc:athena:` | `com.amazon.athena:athena-jdbc` | GENERIC |
| `oracle` | Oracle | `oracle.jdbc.OracleDriver` | `jdbc:oracle:thin:@//{host}:1521/{database}` | `jdbc:oracle:` | `com.oracle.database.jdbc:ojdbc11` | GENERIC |
| `sqlserver` | SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | `jdbc:sqlserver://{host}:1433;databaseName={database};encrypt=true;trustServerCertificate=true` | `jdbc:sqlserver:` | `com.microsoft.sqlserver:mssql-jdbc` | GENERIC |
| `sqlite` | SQLite | `org.sqlite.JDBC` | `jdbc:sqlite:{file}` | `jdbc:sqlite:` | `org.xerial:sqlite-jdbc` | GENERIC |
| `clickhouse` | ClickHouse | `com.clickhouse.jdbc.ClickHouseDriver` | `jdbc:clickhouse://{host}:8123/{database}` | `jdbc:clickhouse:`, `jdbc:ch:` | `com.clickhouse:clickhouse-jdbc` | GENERIC |
| `snowflake` | Snowflake | `net.snowflake.client.jdbc.SnowflakeDriver` | `jdbc:snowflake://{account}.snowflakecomputing.com/?db={database}` | `jdbc:snowflake:` | `net.snowflake:snowflake-jdbc` | GENERIC |

  IAM: wrapper drivers `iamProperties = {wrapperPlugins: "iam", awsProfile: "{profile}", iamRegion: "{region}"}`; redshift `iamUrlFrom="jdbc:redshift://" iamUrlTo="jdbc:redshift:iam://"`, `iamProperties = {Profile: "{profile}", Region: "{region}"}`; athena `iamProperties = {CredentialsProvider: "ProfileCredentials", ProfileName: "{profile}", Region: "{region}"}`. (Exact names are checked against a live connection in Task 8.)

- [ ] **Step 1: Write failing tests**

```kotlin
fun testBuiltInsLoad() {
  val ids = DbDriverRegistry.builtIns.map { it.id }
  assertContainsElements(ids, "mysql", "mariadb", "postgresql", "h2", "aws-wrapper-mysql", "aws-wrapper-postgresql",
                         "redshift", "athena", "oracle", "sqlserver", "sqlite", "clickhouse", "snowflake")
  assertTrue(DbDriverRegistry.builtIns.all { it.builtIn && it.driverClass.isNotBlank() })
}
fun testForUrlPicksLongestPrefix() {
  val r = DbDriverRegistry.getInstance()
  assertEquals("aws-wrapper-mysql", r.forUrl("jdbc:aws-wrapper:mysql://h:3306/x")?.id)
  assertEquals("mysql", r.forUrl("jdbc:p6spy:mysql://h/x")?.id)
  assertEquals("redshift", r.forUrl("jdbc:redshift:iam://c/dev")?.id)
  assertNull(r.forUrl("jdbc:unknown://x"))
}
fun testUserDriverAndOverrideRoundTrip() {
  // save a user driver + an override of "mysql" (changed defaultProperties), serialize state with XmlSerializer,
  // load into a fresh DbDriverRegistry, assert both present; resetToDefault("mysql") restores builtIns' value;
  // remove("mysql") does not remove the built-in.
}
```
- [ ] **Step 2: Run** `./gradlew :db:test --tests 'dev.phantom.db.DbDriverRegistryTest'` → FAIL.
- [ ] **Step 3: Implement definition, registry, `drivers.xml`; register `<applicationService serviceImplementation="dev.phantom.db.core.drivers.DbDriverRegistry"/>`.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `db: driver definitions, built-in presets and app-level driver registry`

---

### Task 3: Data source → driver binding and migration

**Files:**
- Modify: `db/src/main/kotlin/dev/phantom/db/core/DataSources.kt` (`DbDataSource`, `DbDataSourceService.loadState`)
- Test: `db/src/test/kotlin/dev/phantom/db/DbDriverRegistryTest.kt` (append)

**Interfaces:**
- Consumes: `DbDriverRegistry.find/forUrl/builtInFor` (Task 2).
- Produces on `DbDataSource`: `var driverId: String?`, `@MapAnnotation var properties: MutableMap<String, String>`, `var authKind: DbAuthKind = PASSWORD`, `var awsProfile: String = ""`, `var awsRegion: String = ""`; `copy()` copies all. `enum class DbAuthKind { PASSWORD, NONE, AWS_IAM }` in `DataSources.kt`.
- Produces: `fun DbDataSource.driver(): DbDriverDefinition?` — `find(driverId)` → else `forUrl(url)` → else `builtInFor(dbms)`. Also `fun DbDataSource.dialect(): Dbms = driver()?.dialect ?: dbms`.
- `loadState`: data sources with `driverId == null` get `driverId = builtInFor(dbms).id`. `save`: always sets `dbms = driver()?.dialect ?: dbms`.
- All existing reads of `dataSource.dbms` for dialect decisions switch to `dataSource.dialect()` (mechanical; `dbms` stays as the persisted copy).

- [ ] **Step 1: Failing tests**

```kotlin
fun testOldStateMigratesToBuiltInDriver() {
  // XML as written by 0.10.0: <data-source><option name="dbms" value="POSTGRESQL"/>... no driverId
  // deserialize into DbDataSourceService.State, loadState → driverId == "postgresql", dialect() == POSTGRESQL
}
fun testUnknownDriverIdFallsBackByUrl() {
  val ds = DbDataSource().apply { driverId = "someone-elses"; url = "jdbc:aws-wrapper:mysql://h/x"; dbms = Dbms.MYSQL }
  assertEquals("aws-wrapper-mysql", ds.driver()?.id)
}
fun testSavedDataSourceStillWritesDbms() {
  // save a data source bound to "redshift" → serialized XML contains name="dbms" value="GENERIC" and driverId="redshift",
  // and contains no password / awsSecret
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement; replace dialect reads with `dialect()`.**
- [ ] **Step 4: Run full `./gradlew :db:test`** → PASS.
- [ ] **Step 5: Commit** `db: data sources bind to a driver definition; old state migrates`

---

### Task 4: Maven resolver

**Files:**
- Create: `db/src/main/kotlin/dev/phantom/db/core/drivers/MavenResolver.kt`
- Create: `db/src/test/resources/fake-repo/...` (hand-written POMs, `maven-metadata.xml`, tiny jars + `.sha1`)
- Test: `db/src/test/kotlin/dev/phantom/db/MavenResolverTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun interface ArtifactFetcher { fun fetch(url: String): ByteArray? }   // null = 404
  data class MavenCoordinate(val group: String, val artifact: String, val version: String?) {
    companion object { fun parse(s: String): MavenCoordinate }  // "g:a" or "g:a:v"
  }
  data class ResolvedArtifact(val group: String, val artifact: String, val version: String, val jarUrl: String, val sha1Url: String)
  class MavenResolver(private val repositories: List<String>, private val fetcher: ArtifactFetcher) {
    fun latestVersion(group: String, artifact: String): String       // maven-metadata.xml <release>, else <latest>, else max <version>
    fun resolve(roots: List<MavenCoordinate>): List<ResolvedArtifact> // roots first, then deps breadth-first
  }
  ```
- Rules (spec): scopes `compile` and `runtime` (missing scope = compile); skip `test`, `provided`, `system`, `optional=true` (for non-root deps; roots are always included); `parent` chain merged (properties, dependencyManagement, dependencies); `${...}` substitution incl. `project.version`, `project.groupId`, `project.parent.version`; `dependencyManagement` entries with `scope=import type=pom` imported as BOM; `exclusions` (`*` wildcards) propagate down the path; nearest-wins by BFS depth, first declaration wins on tie; version ranges `[a,b)` → pick highest available from metadata within range; packaging other than `jar`/`bundle` → no jar (pom-only). Repositories tried in order; first hit wins. POM parsing via `javax.xml.parsers.DocumentBuilderFactory` with external entities disabled.

- [ ] **Step 1: Failing tests** (fake repo fetcher reads `src/test/resources/fake-repo` by relative path)

```kotlin
fun testParentPropertiesAndDependencyManagement()   // child version from parent's dependencyManagement via ${dep.version}
fun testBomImport()                                  // version comes from imported BOM
fun testExclusionsAndOptionalAreDropped()            // excluded transitive + optional dep absent
fun testNearestWins()                                // A→B→C:1, A→C:2 ⇒ C:2 only
fun testScopes()                                     // test/provided absent, runtime present
fun testLatestVersionFromMetadata()                  // release tag preferred
fun testPomOnlyArtifactHasNoJar()
fun testSecondRepositoryIsUsedWhenFirstMisses()
```
Each asserts the exact `group:artifact:version` set, e.g. `assertEquals(setOf("t:a:1.0", "t:b:1.0", "t:c:2.0"), resolved.map { "${it.group}:${it.artifact}:${it.version}" }.toSet())`.
- [ ] **Step 2: Run** `./gradlew :db:test --tests 'dev.phantom.db.MavenResolverTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `db: minimal Maven resolver (parents, BOMs, exclusions, nearest-wins)`

---

### Task 5: Driver file lookup chain and downloader

**Files:**
- Modify: `db/src/main/kotlin/dev/phantom/db/core/DataSources.kt` (`DbDriver`, `DbDrivers`)
- Create: `db/src/main/kotlin/dev/phantom/db/core/drivers/DbDriverDownloader.kt`
- Test: `db/src/test/kotlin/dev/phantom/db/DbDriverFilesTest.kt`; update `DbConsoleTest.testDriverLookup` / `h2Jar` to the new signatures

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `DbDriver.Origin` gains `DOWNLOADED`.
  - `DbDrivers.find(project: Project?, dataSource: DbDataSource): DbDriver?` — order: data source `driverJars` → definition `files` → project libraries → local Gradle/Maven caches → definition `resolvedJars` (all exist) → null.
    - Project libraries / caches: for built-ins `mysql|mariadb|postgresql|h2`, today's single newest jar of any `artifacts` entry (keeps `versionOf`). For other definitions, every root artifact in `artifacts` must be found; then transitive deps are resolved with `MavenResolver` over a `file://` fetcher on `~/.m2/repository` and a Gradle-cache fetcher (`~/.gradle/caches/modules-2/files-2.1/<g>/<a>/<v>/*/<file>`); if any jar is missing → skip this step.
  - `DbDrivers.fromLocalCaches(definition: DbDriverDefinition, home: Path = ...)` replaces the `Dbms` overload (tests updated).
  - `object DbDriverDownloader { fun download(definition: DbDriverDefinition, indicator: ProgressIndicator, resolver: MavenResolver = default(), target: Path = defaultDir()): DbDriverDefinition }` — resolves, downloads each jar to `<target>/<group>/<artifact>/<version>/<artifact>-<version>.jar` via `<file>.part` then atomic move after SHA-1 check; returns a copy with `resolvedVersion` (version of the first root) and `resolvedJars` set; caller saves it through the registry. `indicator.checkCanceled()` between files; on cancel/error deletes the current `.part`. `defaultDir() = PathManager.getSystemDir().resolve("phantom-db/drivers")`. `default()` = repositories from `DbDriverSettings` (Maven Central `https://repo1.maven.org/maven2/` + user list) with an `HttpRequests` fetcher (404 → null).
  - `@Service(APP) DbDriverSettings` (same `phantomDbDrivers.xml` storage, separate state name `PhantomDbDriverSettings`): `var repositories: MutableList<String>`.

- [ ] **Step 1: Failing tests**

```kotlin
fun testBuiltInKeepsSingleJarFallback()      // h2 data source with no files → Origin.LOCAL_CACHE, jars.size == 1
fun testUserDefinedDriverFromLocalCache()    // user definition, artifacts=["com.h2database:h2"], dialect GENERIC → found in cache
fun testDownloadFromFakeRepoPinsVersion()    // fake repo; artifacts=["t:a"] → resolvedVersion == "1.0", resolvedJars all exist under temp target
fun testChecksumMismatchLeavesNothing()      // fake repo with wrong .sha1 → throws, no *.jar and no *.part under target
fun testCancelLeavesNoPartFile()             // indicator canceled after first file → ProcessCanceledException, no *.part
fun testResolvedJarsAreUsedWhenNothingElse() // definition with resolvedJars (temp files), nothing in caches → Origin.DOWNLOADED
```
- [ ] **Step 2: Run** `./gradlew :db:test --tests 'dev.phantom.db.DbDriverFilesTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run full `./gradlew :db:test`** → PASS.
- [ ] **Step 5: Commit** `db: driver lookup chain over definitions and Maven download cache`

---

### Task 6: Connections from definitions (properties, auth, missing driver)

**Files:**
- Modify: `db/src/main/kotlin/dev/phantom/db/core/exec/DbConnections.kt` (`open`, `loadDriver`, cache keys)
- Modify: `db/src/main/resources/messages/PhantomDbBundle.properties`
- Test: `db/src/test/kotlin/dev/phantom/db/DbGenericDialectTest.kt` (append)

**Interfaces:**
- Consumes: `DbDataSource.driver()`, `DbDrivers.find`, `DbDriverDownloader`.
- Produces:
  - `internal fun DbConnectionService.Companion.connectionProperties(definition: DbDriverDefinition, dataSource: DbDataSource, password: String?): Properties` — order: `defaultProperties` → login-timeout defaults (today's MySQL/PostgreSQL keys by `dialect`) → `dataSource.properties` → auth: `PASSWORD` sets `user`/`password` (password only if non-null), `NONE` sets nothing, `AWS_IAM` sets `user` and `iamProperties` with `{profile}`/`{region}` substituted (blank values → property omitted).
  - `internal fun connectionUrl(definition, dataSource): String` — applies `iamUrlFrom→iamUrlTo` when `AWS_IAM`.
  - Driver cache key `(jars, driverClass)` instead of `(jars, Dbms)`; `loadDriver(jars, driverClass)`.
  - Missing driver: `DbException` message `db.driver.missing.download` = "No driver files for {0}. Download them, or add jars under Drivers." and a balloon in group `Phantom Database` with actions "Download driver" (runs `DbDriverDownloader` as a `Task.Backgroundable`, saves to registry) and "Configure Drivers…". No download without the click.
  - No driver definition at all: message `db.driver.unknown` = "Driver ''{0}'' is not defined in this IDE. Add it under Drivers." with "Configure Drivers…".

- [ ] **Step 1: Failing tests**

```kotlin
fun testGenericUserDriverRunsConsoleAndTree() {
  // registry.save(user definition "my-h2": class org.h2.Driver, prefixes ["jdbc:h2:"], artifacts ["com.h2database:h2"], dialect GENERIC)
  // data source driverId="my-h2", h2 mem URL; exec "CREATE TABLE t(id INT)", "SELECT * FROM t" → Updated, Rows
  // DbMetadataCache.schemas(ds) non-empty, tables include T
}
fun testPropertiesMergeOrderAndIam() {
  val def = DbDriverRegistry.getInstance().find("aws-wrapper-mysql")!!
  val ds = DbDataSource().apply { authKind = DbAuthKind.AWS_IAM; user = "app"; awsProfile = "dev"; awsRegion = ""
                                  properties = mutableMapOf("sslMode" to "REQUIRED") }
  val p = DbConnectionService.connectionProperties(def, ds, password = "x")
  assertEquals("iam", p["wrapperPlugins"]); assertEquals("dev", p["awsProfile"]); assertNull(p["iamRegion"])
  assertEquals("REQUIRED", p["sslMode"]); assertEquals("app", p["user"]); assertNull(p["password"])
}
fun testRedshiftIamRewritesUrl()  // connectionUrl(redshift, ds(AWS_IAM, "jdbc:redshift://c:5439/dev")) == "jdbc:redshift:iam://c:5439/dev"
fun testUnknownDriverErrorMentionsDriver() // driverId "nope", url "jdbc:nope://x" → DbException message contains "nope"
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run full `./gradlew :db:test`** → PASS.
- [ ] **Step 5: Commit** `db: connect through driver definitions with properties and AWS IAM`

---

### Task 7: UI — Drivers editor and data source dialog

**Files:**
- Create: `db/src/main/kotlin/dev/phantom/db/core/ui/DbDriversDialog.kt` (list + detail editor)
- Modify: `db/src/main/kotlin/dev/phantom/db/core/ui/DbDataSourceDialog.kt`
- Modify: `db/src/main/kotlin/dev/phantom/db/core/ui/DbToolWindow.kt` (toolbar/gear "Drivers…")
- Modify: `PhantomDbBundle.properties`
- Test: `db/src/test/kotlin/dev/phantom/db/DbDriverUiTest.kt`

**Interfaces:**
- Consumes: registry, downloader, `MavenResolver.latestVersion`.
- Produces: `DbDriversDialog(project: Project?, select: String? = null)`; `DbDataSourceDialog` driver combo (registry `all`, grouped built-in / user), auth combo (`DbAuthKind`; AWS fields visible only for AWS_IAM and only when the definition has `iamProperties`), properties table (key/value), driver status line with "Download" link and "Drivers…" link; pasting a URL selects `registry.forUrl(url)`; switching driver replaces the URL with the new `urlTemplate` only when the current URL is empty or equals the old template (same rule as today).
- Pure helpers (tested): `internal fun DbDriverDefinition.driverClassesIn(jars: List<Path>): List<String>` — scans jars for `META-INF/services/java.sql.Driver` entries, then classes implementing `java.sql.Driver` loaded in a throwaway loader; `internal fun fillTemplate(template: String, values: Map<String, String>): String`.
- Drivers editor fields: name, class (combo filled by `driverClassesIn`), files (jars) + artifacts list (add coordinate, version combo: `LATEST` + "Check for updates" button calling `latestVersion`), Download button with progress (`Task.Backgroundable`, cancellable), URL template, prefixes, default properties table, dialect combo. Built-ins: Duplicate + Reset; user: Add / Duplicate / Remove. A "Repositories" list (edits `DbDriverSettings.repositories`; Maven Central always first, not removable).

- [ ] **Step 1: Failing tests**

```kotlin
fun testDriverClassesFoundInH2Jar() = assertContainsElements(def.driverClassesIn(listOf(DbConsoleTest.h2Jar)), "org.h2.Driver")
fun testFillTemplate() = assertEquals("jdbc:mysql://db:3306/app", fillTemplate("jdbc:mysql://{host}:3306/{database}", mapOf("host" to "db", "database" to "app")))
fun testDialogPicksDriverFromPastedUrl()  // DbDataSourceDialog(project, null) in test: set urlField text "jdbc:redshift://x:5439/dev" → current().driverId == "redshift"
```
(Expose the dialog's `current()` as `internal` for the test if it is private.)
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run full `./gradlew :db:test`** → PASS; then `./gradlew :db:runIde` and manually: add a user driver from the H2 jar, connect; open Drivers, duplicate MySQL, reset it.
- [ ] **Step 5: Commit** `db: Drivers editor and driver/auth/properties in the data source dialog`

---

### Task 8: Plugin suggestion, docs, version, live AWS check

**Files:**
- Modify: `DataSources.kt` (`projectHasDriverLibrary`), `db/build.gradle.kts` (`version = "0.11.0"`), `db/src/main/resources/META-INF/plugin.xml` (description), `db/README.md`, `db/KOR.md`, root `README.md`/`KOR.md` plugin table if it lists versions
- Test: `DbDriverRegistryTest` (append)

**Interfaces:**
- `projectHasDriverLibrary` matches any `artifacts` of `DbDriverRegistry.builtIns` (names only, no file access, as today).

- [ ] **Step 1: Failing test** `testSuggestionSeesAwsWrapper()` — the name check against a library named `Gradle: software.amazon.jdbc:aws-advanced-jdbc-wrapper:2.5.0` returns true (extract the name predicate as `internal fun isDriverLibraryName(name: String): Boolean` and test it).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run full `./gradlew :db:test`** → PASS.
- [ ] **Step 5: Docs** — README/KOR: replace "MySQL, PostgreSQL, H2용" with the driver model (presets list, user drivers, Maven download on request, AWS IAM, GENERIC limits); remove "no download needed" wording from `plugin.xml` description and say downloads happen only on request.
- [ ] **Step 6: Live check (manual, needs network + AWS access; report results, don't block on it):** `./gradlew :db:runIde` → AWS Wrapper (MySQL) preset → Download → Test Connection with password, then with AWS IAM (profile). Redshift preset: if Test Connection with IAM fails for a missing class, add the missing SDK artifacts to the `redshift` entry in `drivers.xml` and record which. Note anything that couldn't be verified.
- [ ] **Step 7: Commit** `db 0.11.0: dynamic drivers, docs`
