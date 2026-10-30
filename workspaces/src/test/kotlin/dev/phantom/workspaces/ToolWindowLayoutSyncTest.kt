// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import dev.phantom.workspaces.core.ToolWindowLayoutSync
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolWindowLayoutSyncTest {
  @Test
  fun `shared tool windows take the source state, the target's own keep theirs`() {
    val target = mapOf("Project" to "left", "Gradle" to "right")
    val source = mapOf("Project" to "right", "Terminal" to "bottom")
    assertEquals(mapOf("Project" to "right", "Gradle" to "right", "Terminal" to "bottom"), ToolWindowLayoutSync.merge(target, source))
  }
}
