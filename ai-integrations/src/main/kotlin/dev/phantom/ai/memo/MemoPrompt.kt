// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.memo

data class MemoItem(
  val path: String,
  val startLine: Int,
  val endLine: Int,
  val language: String,
  val code: String,
  val text: String,
)

object MemoPrompt {
  fun build(items: List<MemoItem>): String =
    "아래 코드 위치마다 요청을 처리해줘.\n\n" +
      items.withIndex().joinToString("\n\n") { (i, item) -> format(i + 1, item) }

  private fun format(n: Int, item: MemoItem): String {
    val code = strip(item.code)
    val fence = "`".repeat(maxOf(3, longestBacktickRun(code) + 1))
    val range = if (item.startLine == item.endLine) "${item.startLine}" else "${item.startLine}-${item.endLine}"
    val codeLines = code.split("\n").joinToString("\n") { if (it.isEmpty()) "" else "   $it" }
    val memo = strip(item.text).split("\n").joinToString("\n     ")
    return "$n. ${item.path}:$range\n   $fence${item.language}\n$codeLines\n   $fence\n   → $memo"
  }

  private fun strip(s: String) = s.replace("\u001b", "")

  private fun longestBacktickRun(s: String) = Regex("`+").findAll(s).maxOfOrNull { it.value.length } ?: 0
}
