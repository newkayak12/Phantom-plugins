// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import dev.phantom.db.core.*
import dev.phantom.db.core.drivers.DbDriverRegistry
import dev.phantom.db.core.exec.*
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URLClassLoader

/** SSH tunnels (URL rewriting, lifecycle with a fake forwarder) and the SSL property mapping. */
class DbConnectionToolsTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private class FakeTunnel(override val localPort: Int) : DbTunnel {
    @Volatile var closed = false
    @Volatile var alive = true
    override fun isAlive(): Boolean = alive && !closed
    override fun close() {
      closed = true
    }
  }

  private class FakeOpener(private val port: () -> Int) : DbTunnelOpener {
    val opened = ArrayList<Triple<String, String?, DbHostPort>>()
    val tunnels = ArrayList<FakeTunnel>()
    override fun open(dataSource: DbDataSource, secret: String?, target: DbHostPort): DbTunnel = synchronized(this) {
      opened += Triple(dataSource.id, secret, target)
      FakeTunnel(port()).also { tunnels += it }
    }
  }

  override fun tearDown() {
    try {
      val service = DbDataSourceService.getInstance(project)
      service.dataSources.forEach {
        DbConnectionService.getInstance(project).disconnect(it.id)
        service.remove(it.id)
      }
      DbConnectionService.getInstance(project).tunnels.opener = DbSshTunnelOpener(project)
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun def(id: String) = DbDriverRegistry.getInstance().find(id)!!

  private fun ds(url: String, configure: DbDataSource.() -> Unit = {}) = DbDataSource().apply {
    this.url = url
    sshEnabled = true
    sshHost = "bastion"
    configure()
  }

  fun testUrlAddressRewrite() {
    val local = 40000
    fun rewrite(url: String, driver: String?): String {
      val d = driver?.let { def(it) }
      val source = ds(url)
      return DbTunnelUrls.rewrite(url, DbTunnelUrls.target(url, d, source), local)
    }
    assertEquals("jdbc:mysql://127.0.0.1:40000/shop?useSSL=false", rewrite("jdbc:mysql://db.internal:3307/shop?useSSL=false", "mysql"))
    assertEquals(DbHostPort("db.internal", 3306), DbTunnelUrls.target("jdbc:mysql://db.internal/shop", def("mysql"), ds("")))
    assertEquals("jdbc:mysql://127.0.0.1:40000/shop", rewrite("jdbc:mysql://db.internal/shop", "mysql"))
    assertEquals("jdbc:postgresql://127.0.0.1:40000/app", rewrite("jdbc:postgresql://pg:5432/app", "postgresql"))
    assertEquals("jdbc:sqlserver://127.0.0.1:40000;databaseName=x;encrypt=true", rewrite("jdbc:sqlserver://sql:1433;databaseName=x;encrypt=true", "sqlserver"))
    assertEquals("jdbc:oracle:thin:@//127.0.0.1:40000/ORCL", rewrite("jdbc:oracle:thin:@//ora:1521/ORCL", "oracle"))
    assertEquals("jdbc:oracle:thin:@127.0.0.1:40000:ORCL", rewrite("jdbc:oracle:thin:@ora:1521:ORCL", "oracle"))
    assertEquals("jdbc:postgresql://127.0.0.1:40000/app", rewrite("jdbc:postgresql://[fd00::1]:5432/app", "postgresql"))
    assertEquals(DbHostPort("fd00::1", 5432), DbTunnelUrls.target("jdbc:postgresql://[fd00::1]:5432/app", def("postgresql"), ds("")))
    assertEquals("jdbc:mysql://u:p@127.0.0.1:40000/db", rewrite("jdbc:mysql://u:p@my:3306/db", "mysql"))
    assertEquals("jdbc:h2:tcp://127.0.0.1:40000/mem:x", rewrite("jdbc:h2:tcp://h2host:9092/mem:x", "h2"))
    // explicit remote host and port win over the URL's
    assertEquals(DbHostPort("10.0.0.5", 6000), DbTunnelUrls.target("jdbc:mysql://db/x", def("mysql"), ds("") { sshRemoteHost = "10.0.0.5"; sshRemotePort = 6000 }))
    // a URL without an address must name the explicit target literally
    val tns = "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=ora)(PORT=1521)))"
    try {
      DbTunnelUrls.target(tns, def("oracle"), ds(tns))
      fail("no address in a TNS descriptor")
    }
    catch (_: DbException) {
    }
    val failover = "jdbc:mysql://a:3306,b:3306/db"
    assertEquals("jdbc:mysql://127.0.0.1:40000,b:3306/db", DbTunnelUrls.rewrite(failover, DbHostPort("a", 3306), local))
    try {
      DbTunnelUrls.rewrite(tns, DbHostPort("ora", 1521), local)
      fail("the tunnel cannot be put into this URL")
    }
    catch (_: DbException) {
    }
    try {
      DbTunnelUrls.target("jdbc:h2:mem:x", def("h2"), ds("jdbc:h2:mem:x"))
      fail("an in-memory URL has no host")
    }
    catch (_: DbException) {
    }
  }

  fun testTunnelSharedReopenedAndClosed() {
    val opener = FakeOpener { 1234 }
    val tunnels = DbTunnels(opener)
    val a = ds("jdbc:mysql://db:3306/x").apply { sshUser = "me" }
    val target = DbHostPort("db", 3306)
    val first = tunnels.acquire(a, target) { "secret" }
    assertSame(first, tunnels.acquire(a, target) { error("not asked again") })
    assertEquals(listOf(Triple(a.id, "secret", target)), opener.opened)
    // a dead tunnel is replaced
    (first as FakeTunnel).alive = false
    val second = tunnels.acquire(a, target) { null }
    assertNotSame(first, second)
    assertTrue(first.closed)
    // other SSH settings: a new tunnel
    a.sshUser = "other"
    val third = tunnels.acquire(a, target) { null }
    assertTrue((second as FakeTunnel).closed)
    tunnels.close(a.id)
    assertTrue((third as FakeTunnel).closed)
    assertFalse(tunnels.isOpen(a.id))
    val b = ds("jdbc:mysql://db:3306/y")
    val t1 = tunnels.acquire(a, target) { null } as FakeTunnel
    val t2 = tunnels.acquire(b, target) { null } as FakeTunnel
    tunnels.closeAll()
    assertTrue(t1.closed && t2.closed)
  }

  /** A real H2 server stands for the database behind the SSH server; the fake tunnel's local port is the server's. */
  fun testConnectionsGoThroughOneTunnel() {
    val probe = DbDataSource().apply { url = "jdbc:h2:mem:x"; dbms = Dbms.H2 }
    val jars = DbDrivers.find(project, probe)?.jars ?: return fail("H2 is in the Gradle cache for the tests")
    val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
    val serverClass = loader.loadClass("org.h2.tools.Server")
    val server = serverClass.getMethod("createTcpServer", Array<String>::class.java).invoke(null, arrayOf("-tcpPort", "0", "-ifNotExists"))
    serverClass.getMethod("start").invoke(server)
    try {
      val port = serverClass.getMethod("getPort").invoke(server) as Int
      val opener = FakeOpener { port }
      val connections = DbConnectionService.getInstance(project)
      connections.tunnels.opener = opener
      val ds = DbDataSource().apply {
        name = "tunnelled"
        dbms = Dbms.H2
        url = "jdbc:h2:tcp://db.internal:9092/mem:tunnel${System.nanoTime()};DB_CLOSE_DELAY=-1"
        user = "sa"
        sshEnabled = true
        sshHost = "bastion"
        sshAuth = DbSshAuth.PASSWORD
      }
      DbDataSourceService.getInstance(project).save(ds, CharArray(0), "ssh-pass".toCharArray())
      val outcome = runBlocking { DbExecutor.execute(project, ds, listOf("SELECT 41 + 1")) }.single()
      assertEquals(42, ((outcome as DbOutcome.Rows).rows.single()[0] as Number).toInt())
      runBlocking { DbMetadataCache.getInstance(project).schemas(ds) }
      assertEquals("console and schema tree share the tunnel", 1, opener.opened.size)
      assertEquals(DbHostPort("db.internal", 9092), opener.opened.single().third)
      assertEquals("the saved SSH password", "ssh-pass", opener.opened.single().second)

      // Test Connection: a tunnel of its own, closed afterwards
      assertTrue(connections.testConnection(ds, "", "typed").isNotEmpty())
      assertEquals(2, opener.opened.size)
      assertEquals("typed", opener.opened[1].second)
      assertTrue(opener.tunnels[1].closed)
      assertFalse(opener.tunnels[0].closed)

      connections.disconnect(ds.id)
      val deadline = System.currentTimeMillis() + 10_000
      while (!opener.tunnels[0].closed && System.currentTimeMillis() < deadline) Thread.sleep(20)
      assertTrue("disconnect closes the tunnel", opener.tunnels[0].closed)
    }
    finally {
      serverClass.getMethod("stop").invoke(server)
      loader.close()
    }
  }

  fun testSshSecretsOnlyInPasswordSafe() {
    val service = DbDataSourceService.getInstance(project)
    val ds = DbDataSource().apply { name = "s"; url = "jdbc:mysql://db/x"; sshEnabled = true; sshHost = "b"; sshAuth = DbSshAuth.KEY; sshKeyFile = "~/.ssh/id_ed25519" }
    service.save(ds, "db-pass".toCharArray(), "key-phrase".toCharArray())
    assertEquals("key-phrase", service.sshSecret(ds))
    // the password is kept per kind: switching to password auth does not hand over the passphrase
    assertNull(service.sshSecret(ds.copy().apply { sshAuth = DbSshAuth.PASSWORD }))
    assertNull(service.sshSecret(ds.copy().apply { sshAuth = DbSshAuth.AGENT }))
    val xml = JDOMUtil.write(XmlSerializer.serialize(service.state))
    assertFalse(xml.contains("key-phrase"))
    assertFalse(xml.contains("db-pass"))
    assertTrue(xml.contains("id_ed25519"))
    service.remove(ds.id)
    assertNull(service.sshSecret(ds))
  }

  /** The bundled sshj loads and reports a server that does not answer as a tunnel failure. */
  fun testSshjFailsCleanly() {
    // the content module that depends on the sshj library module is loaded: its file type is registered
    assertSame(SqlFileType.INSTANCE, com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("sql"))
    val port = ServerSocket(0).use { it.localPort }
    val ds = DbDataSource().apply { sshEnabled = true; sshHost = "127.0.0.1"; sshPort = port; sshUser = "u" }
    try {
      DbSshTunnelOpener(project) { _, _, _ -> false }.open(ds, "x", DbHostPort("db", 3306))
      fail("nothing listens there")
    }
    catch (e: DbException) {
      assertTrue(e.message, e.message!!.contains("u@127.0.0.1"))
    }
    assertEquals("/home/x/.ssh/id", DbSshTunnelOpener.expandHome("~/.ssh/id").replace(System.getProperty("user.home"), "/home/x"))
  }

  fun testSslPropertiesPerDriver() {
    fun props(driver: String, mode: DbSslMode, ca: String = "", cert: String = "", key: String = "") =
      DbSsl.properties(def(driver), DbDataSource().apply { sslEnabled = true; sslMode = mode; sslCaFile = ca; sslCertFile = cert; sslKeyFile = key })
    assertEquals(mapOf("ssl" to "true", "sslmode" to "verify-full", "sslrootcert" to "/ca.pem", "sslcert" to "/c.pem", "sslkey" to "/k.pk8"),
                 props("postgresql", DbSslMode.VERIFY_FULL, "/ca.pem", "/c.pem", "/k.pk8"))
    assertEquals(mapOf("sslMode" to "VERIFY_CA", "trustCertificateKeyStoreUrl" to "file:/trust.jks"), props("mysql", DbSslMode.VERIFY_CA, "/trust.jks"))
    assertEquals(mapOf("sslMode" to "REQUIRED"), props("aws-wrapper-mysql", DbSslMode.REQUIRE, key = "/ignored"))
    assertEquals(mapOf("sslMode" to "trust"), props("mariadb", DbSslMode.REQUIRE))
    assertEquals(mapOf("encrypt" to "true", "trustServerCertificate" to "true"), props("sqlserver", DbSslMode.REQUIRE))
    assertEquals(mapOf("encrypt" to "true", "trustServerCertificate" to "false", "trustStore" to "/t.jks"), props("sqlserver", DbSslMode.VERIFY_FULL, "/t.jks"))
    assertEquals(mapOf("ssl" to "true", "sslmode" to "strict"), props("clickhouse", DbSslMode.VERIFY_CA))
    // no mapping: the Properties table does it
    assertFalse(DbSsl.supports(def("oracle")))
    assertEquals(emptyMap<String, String>(), props("sqlite", DbSslMode.REQUIRE))
    assertTrue(DbSsl.takesFile(def("postgresql"), DbSsl.KEY))
    assertFalse(DbSsl.takesFile(def("mysql"), DbSsl.KEY))
    // off: nothing
    assertEquals(emptyMap<String, String>(), DbSsl.properties(def("postgresql"), DbDataSource()))
    // the data source's own properties still win
    val ds = DbDataSource().apply { sslEnabled = true; sslMode = DbSslMode.REQUIRE; properties["sslmode"] = "verify-ca" }
    val p = DbConnectionService.connectionProperties(def("postgresql"), ds, null)
    assertEquals("verify-ca", p["sslmode"])
    assertEquals("true", p["ssl"])
  }
}
