// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.sidebar.OptInPrTitles
import dev.phantom.ai.sidebar.PrInfo
import dev.phantom.ai.sidebar.PrTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptInPrTitlesTest {
  private var calls = 0
  private val github = object : PrTitles {
    override fun titleFor(repoPath: String, branch: String): String? = infoFor(repoPath, branch)?.title
    override fun infoFor(repoPath: String, branch: String): PrInfo? = PrInfo(7, "Fix").also { calls++ }
  }

  @Test fun offLooksNothingUp() {
    val titles = OptInPrTitles({ false }, github)
    assertNull(titles.infoFor("/r", "b"))
    assertNull(titles.titleFor("/r", "b"))
    assertEquals(0, calls)
  }

  @Test fun onAsksTheDelegate() {
    val titles = OptInPrTitles({ true }, github)
    assertEquals(PrInfo(7, "Fix"), titles.infoFor("/r", "b"))
    assertEquals("Fix", titles.titleFor("/r", "b"))
    assertEquals(2, calls)
  }
}
