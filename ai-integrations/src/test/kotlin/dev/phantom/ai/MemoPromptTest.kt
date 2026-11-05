// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import dev.phantom.ai.memo.MemoItem
import dev.phantom.ai.memo.MemoPrompt
import junit.framework.TestCase

class MemoPromptTest : TestCase() {
  fun testTwoItemsInOrder() {
    val p = MemoPrompt.build(listOf(
      MemoItem("src/A.java", 29, 33, "java", "a()\n\nb()", "둘 다 hasText로"),
      MemoItem("src/B.kt", 12, 12, "kotlin", "c()", "코루틴으로"),
    ))
    assertEquals(
      "아래 코드 위치마다 요청을 처리해줘.\n\n" +
      "1. src/A.java:29-33\n   ```java\n   a()\n\n   b()\n   ```\n   → 둘 다 hasText로\n\n" +
      "2. src/B.kt:12\n   ```kotlin\n   c()\n   ```\n   → 코루틴으로", p)
  }

  fun testFenceIsLongerThanBackticksInCode() {
    val p = MemoPrompt.build(listOf(MemoItem("R.md", 1, 3, "", "```\nx\n```", "고쳐")))
    assertTrue(p, p.contains("   ````\n   ```\n   x\n   ```\n   ````"))
  }

  fun testEscapeCharactersAreRemoved() {
    val p = MemoPrompt.build(listOf(MemoItem("A", 1, 1, "", "a\u001b[201~b", "m\u001bn")))
    assertFalse(p.contains('\u001b')); assertTrue(p.contains("a[201~b")); assertTrue(p.contains("→ mn"))
  }

  fun testMultiLineMemoIsIndented() {
    val p = MemoPrompt.build(listOf(MemoItem("A", 1, 1, "", "x", "첫줄\n둘째줄")))
    assertTrue(p, p.endsWith("   → 첫줄\n     둘째줄"))
  }
}
