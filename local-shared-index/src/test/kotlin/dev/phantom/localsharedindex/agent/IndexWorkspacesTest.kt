// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The queries on a project on disk, off the EDT as the MCP server calls them: which folder, text search, analysis. */
class IndexWorkspacesTest : HeavyPlatformTestCase() {
  private lateinit var base: Path
  private val api get() = base.resolve("api").toString()

  override fun setUp() {
    super.setUp()
    base = Path.of(project.basePath!!)
    write("api/src/Calc.java", "public class Calc {\n  // the price rule\n  static int twice(int x) { return x * 2; }\n\n\n\n  static int price() { return twice(21); }\n}\n")
    write("api/src/Broken.java", "class Broken {\n  int f() { return missing(); }\n}\n")
    write("other/src/Outside.java", "public class Outside { int price; }")
    for (root in listOf("api/src", "other/src")) PsiTestUtil.addSourceRoot(module, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(root))!!)
    IndexingTestUtil.waitUntilIndexesAreReady(project)
  }

  private fun write(relative: String, text: String) {
    val file = base.resolve(relative)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }

  /** Off the EDT while the EDT keeps running: the search and the analysis need it. */
  private fun <T> offEdt(block: () -> T): T {
    val future = ApplicationManager.getApplication().executeOnPooledThread<Result<T>> { runCatching(block) }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (!future.isDone) {
      check(System.nanoTime() < deadline) { "timed out" }
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      Thread.sleep(10)
    }
    return future.get().getOrThrow()
  }

  fun testSearchTextGivesTheLinesAroundEachMatchInTheWorktreeOnly() {
    assertEquals(
      "src/Calc.java:2\n  1  public class Calc {\n> 2    // the price rule\n  3    static int twice(int x) { return x * 2; }\n" +
        "src/Calc.java:7\n  6  \n> 7    static int price() { return twice(21); }\n  8  }",
      offEdt { IndexWorkspaces.searchText(api, "price", regex = false, caseSensitive = true, context = 1, limit = 10) },
    )
  }

  fun testSearchTextMergesNearMatchesAndSaysWhenThereIsMore() {
    val one = offEdt { IndexWorkspaces.searchText(api, "twice", regex = false, caseSensitive = true, context = 0, limit = 1) }
    assertEquals("src/Calc.java:3\n> 3    static int twice(int x) { return x * 2; }\n... more matches; narrow the text or raise limit", one)
    val regex = offEdt { IndexWorkspaces.searchText(api, "return \\w+\\(", regex = true, caseSensitive = true, context = 0, limit = 10) }
    assertEquals(setOf("src/Broken.java:2", "src/Calc.java:7"), regex.lines().filterNot { it.startsWith(">") }.toSet())
    assertEquals("No line holds \"nothing like this\" in this workspace.",
                 offEdt { IndexWorkspaces.searchText(api, "nothing like this", regex = false, caseSensitive = false, context = 2, limit = 10) })
  }

  fun testABadRegexIsAToolError() {
    val error = runCatching { IndexWorkspaces.searchText(api, "(", regex = true, caseSensitive = true, context = 0, limit = 10) }.exceptionOrNull()
    assertInstanceOf(error, IndexToolError::class.java)
  }

  fun testProblemsAreTheFilesErrors() {
    val broken = offEdt { IndexWorkspaces.problems(api, "src/Broken.java", false) }
    assertTrue(broken, broken.startsWith("src/Broken.java:2  ERROR  ") && broken.contains("missing"))
    assertEquals("No errors in src/Calc.java.", offEdt { IndexWorkspaces.problems(api, "src/Calc.java", false) })
  }

  fun testOutsideTheWorktreeAndNotOpenAreToolErrors() {
    val outside = runCatching { offEdt { IndexWorkspaces.read(api) { it.queries.outline(it.file("../other/src/Outside.java")) } } }.exceptionOrNull()
    assertInstanceOf(outside, IndexToolError::class.java)
    val closed = runCatching { IndexWorkspaces.locate("/nowhere/at/all") }.exceptionOrNull()
    assertEquals(IndexWorkspaces.NOT_OPEN, closed?.message)
  }

  fun testWhileIndexingItSaysSoInsteadOfWaiting() {
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      val error = runCatching { ReadAction.compute<Workspace, Exception> { IndexWorkspaces.locate(api).workspace() } }.exceptionOrNull()
      assertEquals(IndexWorkspaces.INDEXING, error?.message)
    }
  }
}
