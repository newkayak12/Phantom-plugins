// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.sidebar.GithubPluginPrFetcher
import dev.phantom.ai.sidebar.GithubPrTitles
import dev.phantom.ai.sidebar.GithubRepoRef
import java.util.concurrent.TimeUnit

/** The real GitHub-plugin fetcher in a test IDE: no GitHub account is configured, so it answers null without a network call. */
class GithubPluginPrFetcherInIdeTest : BasePlatformTestCase() {
  fun testNoAccountIsNullOffTheEdt() {
    var result: Any? = "unset"
    ApplicationManager.getApplication().executeOnPooledThread {
      result = GithubPluginPrFetcher().fetch(GithubRepoRef("github.com", "acme", "app"), "feat/x")
    }.get(60, TimeUnit.SECONDS)
    assertNull(result)
  }

  fun testRealLookupOnANonRepoIsNull() {
    var result: Any? = "unset"
    ApplicationManager.getApplication().executeOnPooledThread {
      result = GithubPrTitles().infoFor(java.nio.file.Files.createTempDirectory("nopr").toString(), "main")
    }.get(60, TimeUnit.SECONDS)
    assertNull(result)
  }

  fun testDefaultFetcherWiresTheGithubPluginServices() {
    val fetcher = GithubPluginPrFetcher()
    var result: Any? = "unset"
    ApplicationManager.getApplication().executeOnPooledThread {
      result = fetcher.fetch(listOf(GithubRepoRef("github-x", "acme", "app")), "feat/x")
    }.get(60, TimeUnit.SECONDS)
    assertNull(result)
    assertNotNull(ApplicationManager.getApplication().getService(org.jetbrains.plugins.github.authentication.accounts.GHAccountManager::class.java))
  }
}
