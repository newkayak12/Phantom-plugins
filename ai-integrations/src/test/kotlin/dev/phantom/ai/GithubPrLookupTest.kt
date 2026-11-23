// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.sidebar.CachedPrTitles
import dev.phantom.ai.sidebar.GithubPrTitles
import dev.phantom.ai.sidebar.GithubRemote
import dev.phantom.ai.sidebar.GithubRepoRef
import dev.phantom.ai.sidebar.PrFetcher
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PullsResponse
import dev.phantom.ai.sidebar.RemoteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class GithubPrLookupTest {
  private val ref = GithubRepoRef("github.com", "acme", "app")

  @Test
  fun `remote urls parse to owner and repo`() {
    for (u in listOf("https://github.com/acme/app.git", "https://user@github.com/acme/app", "git@github.com:acme/app.git", "ssh://git@github.com:22/acme/app.git", "https://github.com/acme/app/")) {
      assertEquals(u, ref, GithubRemote.parse(u))
    }
    assertEquals(GithubRepoRef("ghe.corp.io", "a", "b"), GithubRemote.parse("git@GHE.corp.io:a/b.git"))
    assertNull(GithubRemote.parse("/local/path/repo"))
    assertNull(GithubRemote.parse("https://github.com/onlyowner"))
  }

  @Test
  fun `origin wins in config lines`() {
    val lines = listOf("remote.up.url git@github.com:up/app.git", "remote.origin.url https://github.com/acme/app.git")
    assertEquals(ref, GithubRemote.fromConfigLines(lines))
    assertEquals(GithubRepoRef("github.com", "up", "app"), GithubRemote.fromConfigLines(lines.take(1)))
    assertNull(GithubRemote.fromConfigLines(emptyList()))
  }

  @Test
  fun sshAliasRemoteParses() {
    assertEquals(GithubRepoRef("github-newkayak", "newkayak12", "Phantom-plugins"), GithubRemote.parse("git@github-newkayak:newkayak12/Phantom-plugins.git"))
  }

  @Test
  fun allRemotesOriginFirst() {
    val lines = listOf(
      "remote.up.url git@github.com:up/app.git",
      "remote.origin.url https://github.com/me/app.git",
      "remote.other.url /local/path",
      "remote.dup.url https://github.com/up/app",
    )
    assertEquals(listOf(GithubRepoRef("github.com", "me", "app"), GithubRepoRef("github.com", "up", "app")), GithubRemote.allFromConfigLines(lines))
    assertEquals(emptyList<GithubRepoRef>(), GithubRemote.allFromConfigLines(emptyList()))
  }

  @Test
  fun urlUsesForkOwnerAsHead() {
    assertEquals(
      "https://api.github.com/repos/up/app/pulls?head=me%3Afeat&state=open",
      PullsResponse.url("https://api.github.com", GithubRepoRef("github.com", "up", "app"), "feat", "me"),
    )
  }

  @Test
  fun `pulls url encodes the head`() {
    assertEquals(
      "https://api.github.com/repos/acme/app/pulls?head=acme%3Afeat%2Fx&state=open",
      PullsResponse.url("https://api.github.com/", ref, "feat/x"),
    )
  }

  @Test
  fun `response parses number and title`() {
    val body = """[{"url":"u","id":1,"number":42,"state":"open","title":"Add X","head":{"ref":"feat/x"}},{"number":7,"title":"other"}]"""
    assertEquals(PrInfo(42, "Add X"), PullsResponse.parse(body))
    assertEquals(
      PrInfo(5, "Fix", "https://github.com/o/r/pull/5"),
      PullsResponse.parse("""[{"number":5,"title":"Fix","html_url":"https://github.com/o/r/pull/5"}]"""),
    )
    assertNull(PullsResponse.parse("[]"))
    assertNull(PullsResponse.parse("""{"message":"Not Found"}"""))
    assertNull(PullsResponse.parse("not json"))
    assertNull(PullsResponse.parse("""[{"number":1,"title":null}]"""))
  }

  private val remotes = RemoteSource { listOf(ref) }

  @Test
  fun `real lookup maps fetch to info and title`() {
    var asked: Pair<GithubRepoRef, String>? = null
    val t = GithubPrTitles(remotes) { r, b -> asked = r to b; PrInfo(5, "T") }
    assertEquals(PrInfo(5, "T"), t.infoFor("/r", "feat/x"))
    assertEquals("T", t.titleFor("/r", "feat/x"))
    assertEquals(ref to "feat/x", asked)
  }

  @Test
  fun `no account, non-github remote or exception is null`() {
    assertNull(GithubPrTitles(remotes) { _, _ -> null }.infoFor("/r", "b"))
    assertNull(GithubPrTitles({ emptyList() }, PrFetcher { _, _ -> error("not asked") }).infoFor("/r", "b"))
    assertNull(GithubPrTitles(remotes) { _, _ -> throw java.io.IOException("offline") }.infoFor("/r", "b"))
    assertNull(GithubPrTitles(RemoteSource { throw IllegalStateException() }, PrFetcher { _, _ -> PrInfo(1, "x") }).titleFor("/r", "b"))
  }

  @Test
  fun `cached lookup asks once and keeps the number`() {
    var calls = 0
    val c = CachedPrTitles(GithubPrTitles(remotes) { _, _ -> calls++; PrInfo(9, "Nine") })
    assertEquals(PrInfo(9, "Nine"), c.infoFor("/r", "b"))
    assertEquals("Nine", c.titleFor("/r", "b"))
    assertEquals(1, calls)
  }

  @Test
  fun `background lookup answers at once and asks once`() {
    val queued = ArrayList<Runnable>()
    var calls = 0
    var loaded = 0
    var now = 0L
    val b = dev.phantom.ai.sidebar.BackgroundPrTitles(
      GithubPrTitles(remotes) { _, _ -> calls++; PrInfo(9, "Nine") }, { queued += it }, { loaded++ }, ttlMillis = 10, clock = { now },
    )
    assertNull(b.infoFor("/r", "b"))
    assertNull(b.infoFor("/r", "b"))
    assertEquals(1, queued.size)
    queued.removeAt(0).run()
    assertEquals(1, loaded)
    assertEquals(PrInfo(9, "Nine"), b.infoFor("/r", "b"))
    assertTrue(queued.isEmpty())
    // stale: the old answer stays until the new one is in, and an unchanged answer refreshes nothing
    now = 10
    assertEquals(PrInfo(9, "Nine"), b.infoFor("/r", "b"))
    queued.removeAt(0).run()
    assertEquals(1, loaded)
    assertEquals(2, calls)
  }
}
