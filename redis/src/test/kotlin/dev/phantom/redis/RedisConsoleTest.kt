// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.redis

import junit.framework.TestCase

class RedisConsoleTest : TestCase() {
  private fun masked(line: String) = RedisConsole.shown(line, RedisText.split(line))

  fun testAsksBeforeDeletingEveryKey() {
    assertEquals("every key in the current database of prod:6379", RedisConsole.wipeScope(listOf("flushdb"), "prod:6379"))
    assertEquals("every key in every database of prod:6379", RedisConsole.wipeScope(listOf("FLUSHALL", "ASYNC"), "prod:6379"))
    assertNull(RedisConsole.wipeScope(listOf("DEL", "k"), "prod:6379"))
    assertNull(RedisConsole.wipeScope(emptyList(), "prod:6379"))
  }

  fun testHidesPasswords() {
    assertEquals("AUTH ***", masked("AUTH s3cret"))
    assertEquals("auth admin ***", masked("auth admin s3cret"))
    assertEquals("HELLO 3 AUTH admin *** SETNAME x", masked("HELLO 3 AUTH admin s3cret SETNAME x"))
    assertEquals("MIGRATE h 6379 k 0 5000 AUTH ***", masked("MIGRATE h 6379 k 0 5000 AUTH s3cret"))
    assertEquals("MIGRATE h 6379 k 0 5000 AUTH2 u ***", masked("MIGRATE h 6379 k 0 5000 AUTH2 u s3cret"))
    assertEquals("ACL SETUSER bob on >*** #*** ~keys:* +get", masked("ACL SETUSER bob on >s3cret #abc123 ~keys:* +get"))
    assertEquals("CONFIG SET maxmemory 1gb requirepass ***", masked("CONFIG SET maxmemory 1gb requirepass s3cret"))
    assertEquals("AUTH ***", masked("AUTH \"pass word\""))
  }

  fun testLeavesOtherCommandsAsTyped() {
    assertEquals("SET greeting \"hello world\"", masked("SET greeting \"hello world\""))
    assertEquals("ACL SETUSER bob on ~* +@all", masked("ACL SETUSER bob on ~* +@all"))
    assertEquals("CONFIG SET maxmemory 1gb", masked("CONFIG SET maxmemory 1gb"))
  }
}
