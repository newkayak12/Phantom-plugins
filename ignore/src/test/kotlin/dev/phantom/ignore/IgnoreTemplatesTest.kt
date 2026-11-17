// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore

import dev.phantom.ignore.templates.IgnoreTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreTemplatesTest {
  @Test
  fun `every template is bundled`() {
    for (name in IgnoreTemplates.NAMES) assertTrue(name, IgnoreTemplates.load(name).isNotBlank())
  }

  @Test
  fun `templates go under a header into an empty file`() {
    assertEquals("### A ###\na\nb\n", IgnoreTemplates.merge("", listOf("A" to "a\nb\n")))
  }

  @Test
  fun `lines the file or an earlier template has are left out`() {
    assertEquals("x\n*.class\n\n### A ###\na\n\n### B ###\nb\n",
                 IgnoreTemplates.merge("x\n*.class", listOf("A" to "*.class\na\n", "B" to "a\nb\n", "C" to "x\n")))
  }
}
