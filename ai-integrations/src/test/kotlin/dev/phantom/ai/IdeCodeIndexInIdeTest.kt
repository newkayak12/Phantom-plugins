// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import dev.phantom.ai.mcp.IdeCodeIndex
import dev.phantom.ai.mcp.McpToolError
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The real index on a project on disk: which project, which folder, off the EDT as the hook server calls it. */
class IdeCodeIndexInIdeTest : HeavyPlatformTestCase() {
  private lateinit var base: Path

  override fun setUp() {
    super.setUp()
    base = Path.of(project.basePath!!)
    write("api/src/Inside.java", "public class Inside {}")
    write("other/src/Outside.java", "public class Outside {}")
    for (root in listOf("api/src", "other/src")) PsiTestUtil.addSourceRoot(module, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(root))!!)
    IndexingTestUtil.waitUntilIndexesAreReady(project)
  }

  private fun write(relative: String, text: String) {
    val file = base.resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }

  private fun <T> offEdt(block: () -> T): T =
    ApplicationManager.getApplication().executeOnPooledThread<Result<T>> { runCatching(block) }.get(60, TimeUnit.SECONDS).getOrThrow()

  private val index = IdeCodeIndex()

  fun testAnswersForTheWholeProjectWithPathsRelativeToIt() {
    assertEquals("api/src/Inside.java:1  public class Inside {}", offEdt { index.findSymbol(base.toString(), "Inside", 5) })
  }

  fun testAWorktreeInsideTheProjectSeesOnlyItsOwnFiles() {
    val api = base.resolve("api").toString()
    assertEquals("src/Inside.java", offEdt { index.findFile(api, "Inside", 5) })
    assertEquals("No symbol matches \"Outside\" in this workspace.", offEdt { index.findSymbol(api, "Outside", 5) })
    assertEquals(McpToolError::class.java, runCatching { offEdt { index.fileOutline(api, "../other/src/Outside.java") } }.exceptionOrNull()?.javaClass)
  }

  fun testAWorkspaceNotOpenIsToldSo() {
    val error = runCatching { offEdt { index.findFile("/nowhere/at/all", "Inside", 5) } }.exceptionOrNull()
    assertInstanceOf(error, McpToolError::class.java)
    assertTrue(error!!.message!!.contains("not open"))
  }
}
