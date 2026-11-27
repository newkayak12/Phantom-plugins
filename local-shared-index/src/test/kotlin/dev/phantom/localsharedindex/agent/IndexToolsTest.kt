// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexToolsTest {
  private val tools = IndexTools.all().associateBy { it.name }

  private fun errorOf(name: String, worktree: String?, vararg args: Pair<String, String>): String? =
    runCatching { tools.getValue(name).call(worktree, mapOf(*args)) }.exceptionOrNull()?.let { (it as IndexToolError).message }

  @Test fun theToolsKeepTheirNamesAndRequiredArguments() {
    assertEquals(listOf("find_symbol", "find_usages", "search_text", "file_outline", "get_file_problems"), IndexTools.all().map { it.name })
    assertEquals(listOf("path", "line", "name"), tools.getValue("find_usages").required)
    assertEquals(listOf("text"), tools.getValue("search_text").required)
    assertTrue(IndexTools.all().all { tool -> tool.required.all { r -> tool.params.any { it.name == r } } })
  }

  @Test fun missingArgumentsAreToldBeforeLookingForTheWorkspace() {
    assertEquals("query is required", errorOf("find_symbol", "/w"))
    assertEquals("line is required", errorOf("find_usages", "/w", "path" to "a.kt", "name" to "A"))
    assertEquals("text is required", errorOf("search_text", "/w"))
    assertEquals("path is required", errorOf("get_file_problems", "/w"))
  }

  @Test fun aTerminalWithoutAWorktreeHasNoIndex() {
    assertEquals(IndexWorkspaces.NOT_AVAILABLE, errorOf("find_symbol", null, "query" to "A"))
    assertEquals(IndexWorkspaces.NOT_AVAILABLE, errorOf("search_text", null, "text" to "A"))
  }
}
