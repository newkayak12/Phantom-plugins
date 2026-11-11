// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.mcp.CodeText
import dev.phantom.ai.mcp.RunOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class RunOutputTest {
  private val text = "start\nINFO a\nERROR b\nINFO c\nERROR d\n"

  @Test fun theLastLines() =
    assertEquals("[App: running] the last 2 of 5 lines\nINFO c\nERROR d", RunOutput.answer("App", "running", text, 2, null))

  @Test fun allLinesWhenFew() = assertEquals("[App] 5 lines\nstart\nINFO a\nERROR b\nINFO c\nERROR d", RunOutput.answer("App", "", text, 100, null))

  @Test fun grepFiltersCaseInsensitively() {
    assertEquals("[App: finished, exit code 1] 2 of 5 lines match\nERROR b\nERROR d", RunOutput.answer("App", "finished, exit code 1", text, 100, "error"))
    assertEquals("[App] 2 of 5 lines match, the last 1\nERROR d", RunOutput.answer("App", "", text, 1, "error"))
    // not a valid regular expression: matched as written
    assertEquals("[App] 0 of 5 lines match", RunOutput.answer("App", "", text, 10, "a("))
  }

  @Test fun codeIsReindented() {
    assertEquals("fun a() {\n    x()\n  }", CodeText.indented("\n  fun a() {\n    x()\n  }\n\n", "  ", indentFirst = false))
    assertEquals("  fun a() {\n\n    x()\n  }", CodeText.indented("fun a() {\n   \n  x()\n}", "  ", indentFirst = true))
  }
}
