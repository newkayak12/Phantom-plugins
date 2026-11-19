// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.mcp.IdeEditorBridge
import dev.phantom.ai.mcp.McpToolError
import java.nio.file.Files
import java.nio.file.Path

/** The MCP tools on a real editor: a file on disk opened at a line with a block selected, and read back as context. */
class IdeEditorBridgeInIdeTest : BasePlatformTestCase() {
  private lateinit var dir: Path

  override fun setUp() {
    super.setUp()
    dir = Files.createTempDirectory("mcpwt").toRealPath()
    com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
    Files.createDirectories(dir.resolve("src"))
    Files.writeString(dir.resolve("src/a.kt"), (1..30).joinToString("\n") { "line $it" })
  }

  override fun tearDown() {
    try {
      FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
      dir.toFile().deleteRecursively()
    } finally {
      super.tearDown()
    }
  }

  /** The bridge waits on the EDT, so it runs on a pooled thread while this (EDT) test pumps events. */
  private fun <T> offEdt(run: () -> T): T =
    PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread<Result<T>> { runCatching(run) }, 30_000).getOrThrow()

  fun testOpensAtTheLineSelectsTheBlockAndReadsItBack() {
    val bridge = IdeEditorBridge { project }
    val opened = offEdt { bridge.open(dir.toString(), "src/a.kt", 10, 3, 12) }
    assertEquals(dir.resolve("src/a.kt").toString(), opened)

    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    assertEquals(9, editor.caretModel.logicalPosition.line)
    assertEquals("line 10\nline 11\nline 12", editor.selectionModel.selectedText)

    val context = offEdt { bridge.context(dir.toString()) }
    assertEquals(dir.resolve("src/a.kt").toString(), context.get("file").asString)
    assertEquals(10, context.get("selectionStartLine").asInt)
    assertEquals(12, context.get("selectionEndLine").asInt)
    assertEquals("line 10\nline 11\nline 12", context.get("selectedText").asString)
  }

  fun testALineBeyondTheEndGoesToTheLastLine() {
    offEdt { IdeEditorBridge { project }.open(null, dir.resolve("src/a.kt").toString(), 999, null, null) }
    assertEquals(29, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
  }

  fun testMissingFileAndRelativePathWithoutWorktreeAreToolErrors() {
    val bridge = IdeEditorBridge { project }
    for (call in listOf({ bridge.open(dir.toString(), "nope.kt", 1, null, null) }, { bridge.open(null, "src/a.kt", 1, null, null) })) {
      try {
        offEdt(call)
        fail("expected a tool error")
      } catch (e: Exception) {
        assertTrue(e.toString(), e is McpToolError)
      }
    }
  }
}
