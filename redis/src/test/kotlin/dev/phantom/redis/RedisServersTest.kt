// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Saving a server keeps the password safe out of it: the panel writes the password on its executor, off the EDT. */
class RedisServersTest : BasePlatformTestCase() {
  fun testSaveAndRemoveLeaveThePasswordToStorePassword() {
    val servers = RedisServers.getInstance(project)
    val server = RedisServer().apply { id = "test-server"; host = "cache" }
    servers.storePassword(server.id, "s3cret")

    servers.save(server)
    assertEquals(listOf("test-server"), servers.servers.map { it.id })
    assertEquals("s3cret", servers.password(server.id))

    servers.remove(server.id)
    assertEquals("s3cret", servers.password(server.id))
    servers.storePassword(server.id, "")
    assertNull(servers.password(server.id))
  }
}
