// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.sidebar.WorktreeTitle
import junit.framework.TestCase

class WorktreeTitleTest : TestCase() {
  fun testUserNameBeatsPrTitleBeatsBranch() {
    assertEquals("mine", WorktreeTitle.resolve("mine", "PR t", "feat/x", "/r/wt"))
    assertEquals("PR t", WorktreeTitle.resolve(null, "PR t", "feat/x", "/r/wt"))
    assertEquals("feat/x", WorktreeTitle.resolve(null, null, "feat/x", "/r/wt"))
  }

  fun testBlankValuesAreSkippedAndDetachedFallsBackToDirectory() {
    assertEquals("PR t", WorktreeTitle.resolve("  ", "PR t", "b", "/r/wt"))
    assertEquals("wt", WorktreeTitle.resolve("", "", null, "/r/wt/"))
  }
}
