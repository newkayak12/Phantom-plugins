// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.terminal.SessionJsonl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class SessionJsonlTest {
  @get:Rule val tmp = TemporaryFolder()

  private val a = "11111111-1111-1111-1111-111111111111"
  private val b = "22222222-2222-2222-2222-222222222222"
  private val c = "33333333-3333-3333-3333-333333333333"

  @Test fun everyNonAlphanumericBecomesDash() {
    assertEquals("-Users-me-my-repo-feat-x", SessionJsonl.encode("/Users/me/my repo/feat.x".replace("my repo", "my-repo").replace("feat.x", "feat-x")))
    assertEquals("-Users-me--orca-a-b", SessionJsonl.encode("/Users/me/.orca/a b"))
    assertEquals("-", SessionJsonl.encode("/"))
    assertEquals("-tmp-x", SessionJsonl.encode("/tmp/x/"))
  }

  private fun file(dir: Path, name: String, time: Long) {
    Files.createDirectories(dir)
    val f = dir.resolve(name)
    Files.writeString(f, "{}")
    Files.setLastModifiedTime(f, FileTime.fromMillis(time))
  }

  @Test fun listsUuidJsonlNewestFirstAndIgnoresOthers() {
    val cfg = tmp.newFolder("cfg").toPath()
    val dir = cfg.resolve("projects").resolve("-w-repo-wt")
    file(dir, "$a.jsonl", 1000)
    file(dir, "$b.jsonl", 3000)
    file(dir, "$c.jsonl", 2000)
    file(dir, "notes.jsonl", 9000)
    file(dir, "$a.txt", 9000)
    file(cfg.resolve("projects").resolve("-other"), "$a.jsonl", 9000)
    assertEquals(listOf(b, c, a), SessionJsonl.sessionIds(cfg, "/w/repo/wt"))
  }

  @Test fun missingProjectDirGivesEmpty() {
    assertEquals(emptyList<String>(), SessionJsonl.sessionIds(tmp.newFolder("empty").toPath(), "/w/none"))
  }

  @Test fun sessionsReportCreationAndModificationTimes() {
    val cfg = tmp.newFolder("times").toPath()
    val dir = cfg.resolve("projects").resolve("-w-repo-wt")
    file(dir, "$a.jsonl", 5000)
    file(dir, "$b.jsonl", 7000)
    val created = 1_600_000_000_000L
    val settable = runCatching {
      Files.setAttribute(dir.resolve("$a.jsonl"), "creationTime", FileTime.fromMillis(created))
      Files.getAttribute(dir.resolve("$a.jsonl"), "creationTime") as FileTime
    }.getOrNull()?.toMillis() == created
    val got = SessionJsonl.sessions(cfg, "/w/repo/wt")
    assertEquals(listOf(b, a), got.map { it.id })
    assertEquals(listOf(7000L, 5000L), got.map { it.modifiedMs })
    // without a settable creation time the file system value is "now" or the mtime; it is never zero
    got.forEach { assertTrue(it.createdMs > 0) }
    if (settable) assertEquals(created, got.single { it.id == a }.createdMs)
    assertEquals(got.map { it.id }, SessionJsonl.sessionIds(cfg, "/w/repo/wt"))
  }
}
