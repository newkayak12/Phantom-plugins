// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
    // several hosts: the driver could reach one outside the tunnel
    for (multi in listOf("jdbc:mysql://a:3306,b:3306/db", "jdbc:postgresql://h1:5432,h2:5432/app", "jdbc:mysql://address=(host=a)(port=3306),address=(host=b)/db")) {
      try {
        DbTunnelUrls.rewrite(multi, DbHostPort("a", 3306), local)
        fail(multi)
      }
      catch (_: DbException) {
      }
      try {
        DbTunnelUrls.target(multi, def("mysql"), ds(multi) { sshRemoteHost = "a"; sshRemotePort = 3306 })
        fail(multi)
      }
      catch (_: DbException) {
      }
    }
    // Oracle: the address after @ even when a // comes later
    assertEquals("jdbc:oracle:thin:@127.0.0.1:40000/svc?x=//y", rewrite("jdbc:oracle:thin:@ora:1521/svc?x=//y", "oracle"))
    // a SQL Server named instance finds its port over UDP
    try {
      DbTunnelUrls.target("jdbc:sqlserver://sql\\inst;databaseName=x", def("sqlserver"), ds(""))
      fail("named instance")
    }
    catch (e: DbException) {
      assertTrue(e.message, e.message!!.contains("inst"))
    }
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

  fun testTunnelSharedCountedReopenedAndClosed() {
    val opener = FakeOpener { 1234 }
    val tunnels = DbTunnels(opener)
    val a = ds("jdbc:mysql://db:3306/x").apply { sshUser = "me" }
    val target = DbHostPort("db", 3306)
    val first = tunnels.acquire(a, target) { "secret" }
    val second = tunnels.acquire(a, target) { error("not asked again") }
    assertSame(first.tunnel, second.tunnel)
    assertEquals(listOf(Triple(a.id, "secret", target)), opener.opened)
    // closed with the last user only
    first.release()
    first.release()
    assertFalse((first.tunnel as FakeTunnel).closed)
    second.release()
    assertTrue((first.tunnel as FakeTunnel).closed)
    assertFalse(tunnels.isOpen(a.id))
    // a dead tunnel is replaced; the old one closes when its user lets go
    val third = tunnels.acquire(a, target) { null }
    (third.tunnel as FakeTunnel).alive = false
    val fourth = tunnels.acquire(a, target) { null }
    assertNotSame(third.tunnel, fourth.tunnel)
    third.release()
    assertTrue((third.tunnel as FakeTunnel).closed)
    // other SSH settings: a new tunnel
    a.sshUser = "other"
    val fifth = tunnels.acquire(a, target) { null }
    assertNotSame(fourth.tunnel, fifth.tunnel)
    // disconnect: no longer handed out, closed once released
    tunnels.detach(a.id)
    assertFalse(tunnels.isOpen(a.id))
    assertFalse((fifth.tunnel as FakeTunnel).closed)
    fifth.release()
    assertTrue((fifth.tunnel as FakeTunnel).closed)
    fourth.release()
    val b = ds("jdbc:mysql://db:3306/y")
    val t1 = tunnels.acquire(a, target) { null }.tunnel as FakeTunnel
    val t2 = tunnels.acquire(b, target) { null }.tunnel as FakeTunnel
    tunnels.closeAll()
    assertTrue(t1.closed && t2.closed)
  }

  /** Opening (slow connect, host key prompt) happens outside the lock: other calls do not wait for it; a second user waits for the same tunnel. */
  fun testOpeningDoesNotHoldTheLock() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val proceed = java.util.concurrent.CountDownLatch(1)
    val opens = java.util.concurrent.atomic.AtomicInteger()
    val tunnels = DbTunnels { _, _, _ ->
      opens.incrementAndGet()
      entered.countDown()
      proceed.await()
      FakeTunnel(1)
    }
    val a = ds("jdbc:mysql://db:3306/x")
    val target = DbHostPort("db", 3306)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      val one = pool.submit<DbTunnelLease> { tunnels.acquire(a, target) { null } }
      assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
      val two = pool.submit<DbTunnelLease> { tunnels.acquire(a, target) { null } }
      // would block on the monitor if acquire held it while opening
      tunnels.detach("other")
      assertTrue(tunnels.isOpen(a.id))
      proceed.countDown()
      assertSame(one.get(10, java.util.concurrent.TimeUnit.SECONDS).tunnel, two.get(10, java.util.concurrent.TimeUnit.SECONDS).tunnel)
      assertEquals(1, opens.get())
    }
    finally {
      proceed.countDown()
      pool.shutdownNow()
    }
  }

  fun testFailedOpenIsNotKept() {
    var fail = true
    val tunnels = DbTunnels { _, _, _ -> if (fail) throw DbException("refused") else FakeTunnel(2) }
    val a = ds("jdbc:mysql://db:3306/x")
    try {
      tunnels.acquire(a, DbHostPort("db", 3306)) { null }
      fail("refused")
    }
    catch (_: DbException) {
    }
    assertFalse(tunnels.isOpen(a.id))
    fail = false
    assertEquals(2, tunnels.acquire(a, DbHostPort("db", 3306)) { null }.tunnel.localPort)
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

  /** Every secret is in one password safe entry (one keychain dialog, not one per data source); an old entry moves into it. */
  fun testSecretsShareOneEntry() {
    val safe = com.intellij.ide.passwordSafe.PasswordSafe.instance
    val old = DbDataSourceService.credentials("legacy-${System.nanoTime()}")
    safe.set(old, com.intellij.credentialStore.Credentials("u", "old=pass: #1"))
    dev.phantom.db.core.DbSecrets.reset()
    assertEquals("old=pass: #1", dev.phantom.db.core.DbSecrets.get(old))
    assertNull(safe.getPassword(old))
    dev.phantom.db.core.DbSecrets.reset()
    assertEquals("old=pass: #1", dev.phantom.db.core.DbSecrets.get(old))

    val other = DbDataSourceService.credentials("new-${System.nanoTime()}")
    dev.phantom.db.core.DbSecrets.set(other, "u", "second".toCharArray())
    assertNull(safe.getPassword(other))
    dev.phantom.db.core.DbSecrets.reset()
    assertEquals("second", dev.phantom.db.core.DbSecrets.get(other))

    dev.phantom.db.core.DbSecrets.set(old, "u", null)
    dev.phantom.db.core.DbSecrets.set(other, "u", null)
    dev.phantom.db.core.DbSecrets.reset()
    assertNull(dev.phantom.db.core.DbSecrets.get(old))
    assertNull(dev.phantom.db.core.DbSecrets.get(other))
  }

  /** The bundled sshj loads and reports a server that does not answer as a tunnel failure. */
  fun testSshjFailsCleanly() {
    // the content module that depends on the sshj library module is loaded: its file type is registered
    assertSame(SqlFileType.INSTANCE, com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("sql"))
    val port = ServerSocket(0).use { it.localPort }
    val ds = DbDataSource().apply { sshEnabled = true; sshHost = "127.0.0.1"; sshPort = port; sshUser = "u" }
    try {
      DbSshTunnelOpener(project, confirmHostKey = { _, _, _ -> false }).open(ds, "x", DbHostPort("db", 3306))
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
    // through a tunnel, verify full checks the real host where the driver can be told it (SQL Server), else the dialog warns
    val tunnelled = DbDataSource().apply { sshEnabled = true; sslEnabled = true; sslMode = DbSslMode.VERIFY_FULL }
    assertEquals(mapOf("hostNameInCertificate" to "sql.internal"), DbSsl.tunnelProperties(def("sqlserver"), tunnelled, "sql.internal"))
    assertEquals(emptyMap<String, String>(), DbSsl.tunnelProperties(def("postgresql"), tunnelled, "pg"))
    assertTrue(DbSsl.verifyFullBreaksWithTunnel(def("postgresql"), tunnelled))
    assertFalse(DbSsl.verifyFullBreaksWithTunnel(def("sqlserver"), tunnelled))
    assertFalse(DbSsl.verifyFullBreaksWithTunnel(def("postgresql"), tunnelled.copy().apply { sslMode = DbSslMode.VERIFY_CA }))
    assertTrue(DbSsl.isKeystore(def("mysql"), DbSsl.CA))
    assertFalse(DbSsl.isKeystore(def("mariadb"), DbSsl.CA))
    assertFalse(DbSsl.isKeystore(def("postgresql"), DbSsl.CA))
    // off: nothing
    assertEquals(emptyMap<String, String>(), DbSsl.properties(def("postgresql"), DbDataSource()))
    // the data source's own properties still win
    val ds = DbDataSource().apply { sslEnabled = true; sslMode = DbSslMode.REQUIRE; properties["sslmode"] = "verify-ca" }
    val p = DbConnectionService.connectionProperties(def("postgresql"), ds, null)
    assertEquals("verify-ca", p["sslmode"])
    assertEquals("true", p["ssl"])
  }
}
