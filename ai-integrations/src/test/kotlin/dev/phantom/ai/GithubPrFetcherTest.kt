// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.progress.ProcessCanceledException
import dev.phantom.ai.sidebar.GithubApi
import dev.phantom.ai.sidebar.GithubPluginPrFetcher
import dev.phantom.ai.sidebar.GithubRepoRef
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.SshConfigHosts
import dev.phantom.ai.sidebar.SshHostResolver
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fetcher with fake accounts, tokens and api, and ssh-config text: no network, no real token, no ~/.ssh. */
class GithubPrFetcherTest {
  private data class Call(val server: GithubServerPath, val token: String, val url: String)

  private val pr = """[{"number":7,"title":"Title"}]"""
  private val dotCom = GithubServerPath.DEFAULT_SERVER
  private val calls = mutableListOf<Call>()

  private fun account(login: String, server: GithubServerPath = dotCom) = GithubAccount(login, server, "id-$login")

  private fun fetcher(
    accounts: List<GithubAccount>,
    ssh: SshHostResolver = SshHostResolver { null },
    token: (GithubAccount) -> String? = { "t" },
    api: (Call) -> String? = { null },
  ) = GithubPluginPrFetcher(
    { accounts },
    { token(it) },
    GithubApi { s, t, u -> Call(s, t, u).also { calls += it }.let(api) },
    ssh,
  )

  private val one = GithubRepoRef("github.com", "o", "r")

  @Test
  fun successPathAccountTokenApiParsedPr() {
    val f = fetcher(listOf(account("me"))) { pr }
    assertEquals(PrInfo(7, "Title"), f.fetch(one, "feat"))
    assertEquals(listOf(Call(dotCom, "t", "https://api.github.com/repos/o/r/pulls?head=o%3Afeat&state=open")), calls)
  }

  @Test
  fun sshAliasMatchesGithubComViaSshConfig() {
    val ssh = SshConfigHosts { "Host github-work\n  HostName github.com\n  User git\n\nHost *\n  HostName nowhere\n" }
    val f = fetcher(listOf(account("me")), ssh) { pr }
    assertEquals(PrInfo(7, "Title"), f.fetch(GithubRepoRef("github-work", "o", "r"), "feat"))
    assertEquals("https://api.github.com/repos/o/r/pulls?head=o%3Afeat&state=open", calls.single().url)
    // a dotless github* alias that the config does not know still means github.com
    assertEquals(PrInfo(7, "Title"), fetcher(listOf(account("me"))) { pr }.fetch(GithubRepoRef("github-other", "o", "r"), "feat"))
  }

  @Test
  fun sshAliasPrefersAccountNamedLikeTheAlias() {
    val f = fetcher(listOf(account("alice"), account("newkayak12"), account("bob")), token = { "tok-${it.name}" }) { pr }
    f.fetch(GithubRepoRef("github-newkayak12", "o", "r"), "feat")
    assertEquals("tok-newkayak12", calls.single().token)
    calls.clear()
    f.fetch(GithubRepoRef("github.com", "bob", "r"), "feat")
    assertEquals("tok-bob", calls.single().token)
    calls.clear()
    f.fetch(GithubRepoRef("github.com", "zed", "r"), "feat")
    assertEquals("tok-alice", calls.single().token)
  }

  @Test
  fun gheSshHostMatchesGheServerAndUsesApiV3() {
    val ghe = GithubServerPath("ghe.corp.com")
    val f = fetcher(listOf(account("me"), account("me", ghe))) { pr }
    assertEquals(PrInfo(7, "Title"), f.fetch(GithubRepoRef("ssh.ghe.corp.com", "o", "r"), "feat"))
    assertEquals(ghe, calls.single().server)
    assertEquals("https://ghe.corp.com/api/v3/repos/o/r/pulls?head=o%3Afeat&state=open", calls.single().url)
    assertNull(fetcher(listOf(account("me", ghe))) { pr }.fetch(GithubRepoRef("github.com", "o", "r"), "feat"))
  }

  @Test
  fun forkPrIsFoundOnUpstreamRemote() {
    val f = fetcher(listOf(account("me"))) { if ("/repos/up/app/pulls" in it.url) pr else "[]" }
    val info = f.fetch(listOf(GithubRepoRef("github.com", "me", "app"), GithubRepoRef("github.com", "up", "app")), "feat")
    assertEquals(PrInfo(7, "Title"), info)
    assertEquals(
      listOf(
        "https://api.github.com/repos/me/app/pulls?head=me%3Afeat&state=open",
        "https://api.github.com/repos/up/app/pulls?head=me%3Afeat&state=open",
      ),
      calls.map { it.url },
    )
  }

  @Test
  fun forkPrIsFoundOnParentRepoWithoutUpstreamRemote() {
    val f = fetcher(listOf(account("me"))) {
      when {
        it.url.endsWith("/repos/me/app") -> """{"parent":{"name":"app","owner":{"login":"up"}}}"""
        "/repos/up/app/pulls" in it.url -> pr
        else -> "[]"
      }
    }
    assertEquals(PrInfo(7, "Title"), f.fetch(GithubRepoRef("github.com", "me", "app"), "feat"))
    assertEquals("https://api.github.com/repos/up/app/pulls?head=me%3Afeat&state=open", calls.last().url)
    assertTrue(calls.any { it.url == "https://api.github.com/repos/me/app" })
  }

  @Test
  fun noTokenIsNull() {
    assertNull(fetcher(listOf(account("me")), token = { null }) { pr }.fetch(one, "feat"))
    assertNull(fetcher(emptyList()) { pr }.fetch(one, "feat"))
    assertTrue(calls.isEmpty())
  }

  @Test
  fun apiFailureIsNull() {
    assertNull(fetcher(listOf(account("me"))) { throw java.io.IOException("offline") }.fetch(one, "feat"))
    assertNull(fetcher(listOf(account("me"))) { "not json" }.fetch(one, "feat"))
  }

  @Test(expected = ProcessCanceledException::class)
  fun processCanceledPropagates() {
    fetcher(listOf(account("me"))) { throw ProcessCanceledException() }.fetch(one, "feat")
  }
}
