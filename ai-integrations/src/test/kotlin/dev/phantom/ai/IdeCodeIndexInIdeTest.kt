// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import dev.phantom.ai.mcp.AgentTools
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
    write("other/src/Outside.java", "public class Outside { Inside ref; }")
    write("api/src/Calc.java", "public class Calc {\n  static int twice(int x) { return x * 2; }\n}\n")
    write("api/src/UseCalc.java", "class UseCalc {\n  int f() { return Calc.twice(1); }\n}\n")
    write("api/src/Broken.java", "class Broken {\n  int f() { return missing(); }\n}\n")
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

  /** A Local Shared Index tool as the MCP server calls it, through its registration in this plugin. */
  private fun tool(name: String, worktree: String, vararg args: Pair<String, String>): String =
    AgentTools.all().single { it.name == name }.call(worktree, JsonObject().apply { args.forEach { (k, v) -> addProperty(k, v) } })

  fun testTheLocalSharedIndexToolsAreRegisteredOnce() {
    val names = AgentTools.all().map { it.name }
    for (name in listOf("find_symbol", "find_usages", "search_text", "file_outline", "get_file_problems")) assertEquals(name, 1, names.count { it == name })
  }

  fun testSearchTextAnswersThroughTheServer() {
    assertEquals("src/Calc.java:2\n> 2    static int twice(int x) { return x * 2; }", offEdtPumping { tool("search_text", api, "text" to "x * 2", "context" to "0") })
  }

  fun testAnswersForTheWholeProjectWithPathsRelativeToIt() {
    assertEquals("api/src/Inside.java:1  public class Inside {}", offEdt { tool("find_symbol", base.toString(), "query" to "Inside") })
  }

  fun testAWorktreeInsideTheProjectSeesOnlyItsOwnFiles() {
    val api = base.resolve("api").toString()
    assertEquals("src/Inside.java", offEdt { index.findFile(api, "Inside", 5) })
    assertEquals("No symbol matches \"Outside\" in this workspace.", offEdt { tool("find_symbol", api, "query" to "Outside") })
    assertEquals(McpToolError::class.java, runCatching { offEdt { tool("file_outline", api, "path" to "../other/src/Outside.java") } }.exceptionOrNull()?.javaClass)
  }

  /** Off the EDT while the EDT keeps running: rename and the analysis wait for it. */
  private fun <T> offEdtPumping(block: () -> T): T {
    val future = ApplicationManager.getApplication().executeOnPooledThread<Result<T>> { runCatching(block) }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (!future.isDone) {
      check(System.nanoTime() < deadline) { "timed out" }
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      Thread.sleep(10)
    }
    return future.get().getOrThrow()
  }

  private val api get() = base.resolve("api").toString()

  fun testProblemsAreTheFilesErrors() {
    val broken = offEdtPumping { tool("get_file_problems", api, "path" to "src/Broken.java") }
    assertTrue(broken, broken.startsWith("src/Broken.java:2  ERROR  ") && broken.contains("missing"))
    assertEquals("No errors in src/Calc.java.", offEdtPumping { tool("get_file_problems", api, "path" to "src/Calc.java") })
  }

  fun testProblemsSeeWhatTheAgentJustWrote() {
    Files.writeString(base.resolve("api/src/Calc.java"), "public class Calc {\n  static int twice(int x) { return nope; }\n}\n")
    assertTrue(offEdtPumping { tool("get_file_problems", api, "path" to "src/Calc.java") }.startsWith("src/Calc.java:2  ERROR  "))
  }

  fun testRenameChangesTheReferencesAndSavesThem() {
    val said = offEdtPumping { index.rename(api, "src/UseCalc.java", 2, "twice", "doubled") }
    assertTrue(said, said.startsWith("Renamed twice to doubled: ") && said.endsWith("saved 2 files:\nsrc/Calc.java\nsrc/UseCalc.java"))
    assertEquals("class UseCalc {\n  int f() { return Calc.doubled(1); }\n}\n", Files.readString(base.resolve("api/src/UseCalc.java")))
    assertTrue(Files.readString(base.resolve("api/src/Calc.java")).contains("static int doubled(int x)"))
  }

  fun testRenameRefusesAChangeOutsideTheWorktree() {
    val error = runCatching { offEdtPumping { index.rename(api, "src/Inside.java", 1, "Inside", "Within") } }.exceptionOrNull()
    assertInstanceOf(error, McpToolError::class.java)
    assertTrue(error!!.message, error.message!!.contains("outside this workspace"))
    assertEquals("public class Inside {}", Files.readString(base.resolve("api/src/Inside.java")))
    assertEquals("public class Outside { Inside ref; }", Files.readString(base.resolve("other/src/Outside.java")))
  }

  fun testReadSymbolReadsOneDeclaration() {
    assertEquals("src/Calc.java:2-2\n2    static int twice(int x) { return x * 2; }", offEdt { index.readSymbol(api, "src/Calc.java", null, "Calc.twice") })
    assertEquals("src/Calc.java:1-3\n1  public class Calc {\n2    static int twice(int x) { return x * 2; }\n3  }", offEdt { index.readSymbol(api, "src/Calc.java", null, "Calc") })
    // a call resolves to the declaration it calls
    assertTrue(offEdt { index.readSymbol(api, "src/UseCalc.java", 2, "twice") }.startsWith("src/Calc.java:2-2"))
    val error = runCatching { offEdt { index.readSymbol(api, "src/Calc.java", null, "thrice") } }.exceptionOrNull()
    assertInstanceOf(error, McpToolError::class.java)
  }

  fun testReplaceSymbolRewritesTheDeclarationAtItsIndentation() {
    val said = offEdtPumping { index.replaceSymbol(api, "src/Calc.java", null, "twice", "static int twice(int x) {\n  int y = x + x;\n  return y;\n}") }
    assertEquals("Replaced twice in src/Calc.java: now lines 2-5. get_file_problems shows any errors.", said)
    assertEquals(
      "public class Calc {\n  static int twice(int x) {\n    int y = x + x;\n    return y;\n  }\n}\n",
      Files.readString(base.resolve("api/src/Calc.java")),
    )
  }

  fun testInsertCodeAddsBeforeAndAfter() {
    offEdtPumping { index.insertCode(api, "src/Calc.java", null, "twice", true, "    static int thrice(int x) {\n      return x * 3;\n    }\n") }
    offEdtPumping { index.insertCode(api, "src/Calc.java", null, "twice", false, "static int once(int x) { return x; }") }
    assertEquals(
      "public class Calc {\n  static int once(int x) { return x; }\n\n  static int twice(int x) { return x * 2; }\n\n" +
        "  static int thrice(int x) {\n    return x * 3;\n  }\n}\n",
      Files.readString(base.resolve("api/src/Calc.java")),
    )
  }

  fun testEditsStayInTheWorktree() {
    val error = runCatching { offEdtPumping { index.replaceSymbol(api, "../other/src/Outside.java", null, "Outside", "class X {}") } }.exceptionOrNull()
    assertInstanceOf(error, McpToolError::class.java)
    assertEquals("public class Outside { Inside ref; }", Files.readString(base.resolve("other/src/Outside.java")))
  }

  fun testAWorkspaceNotOpenIsToldSo() {
    val error = runCatching { offEdt { index.findFile("/nowhere/at/all", "Inside", 5) } }.exceptionOrNull()
    assertInstanceOf(error, McpToolError::class.java)
    assertTrue(error!!.message!!.contains("not open"))
  }
}
