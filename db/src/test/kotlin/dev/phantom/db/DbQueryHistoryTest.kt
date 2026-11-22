// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.exec.DbOutcome
import dev.phantom.db.core.exec.DbQueryHistory

/** What Query History keeps: never passwords, nothing of a data source with history off. */
class DbQueryHistoryTest : BasePlatformTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }
  }

  private fun updated(sql: String) = DbOutcome.Updated(sql, 0, 1)

  fun testStatementsWithPasswordsAreNotKept() {
    for (sql in listOf("ALTER USER app IDENTIFIED BY 's3cret'", "create user bob with password 's3cret'", "SET PASSWORD FOR app = 'x'",
                       "ALTER ROLE app PASSWORD 'x'", "CREATE USER u IDENTIFIED WITH caching_sha2_password BY 'x'",
                       "GRANT ALL ON db.* TO u IDENTIFIED BY 'x'")) {
      assertTrue(sql, DbQueryHistory.sensitive(sql))
    }
    for (sql in listOf("SELECT password FROM users", "UPDATE users SET name = 'x'", "CREATE TABLE t (id int)")) {
      assertFalse(sql, DbQueryHistory.sensitive(sql))
    }
  }

  fun testRecordingRespectsTheDataSource() {
    val history = DbQueryHistory.getInstance(project)
    history.clear()
    val kept = DbDataSource().apply { name = "kept" }
    val off = DbDataSource().apply { name = "off"; queryHistory = false }
    history.record(kept, updated("UPDATE t SET a = 1"))
    history.record(kept, updated("ALTER USER app IDENTIFIED BY 's3cret'"))
    history.record(off, updated("UPDATE customers SET email = 'a@b.c'"))
    assertEquals(listOf("UPDATE t SET a = 1"), history.entries.map { it.sql })
    history.forget(kept.id)
    assertTrue(history.entries.isEmpty())
  }
}
