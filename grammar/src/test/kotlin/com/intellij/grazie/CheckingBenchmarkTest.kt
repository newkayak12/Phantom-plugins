package com.intellij.grazie

import com.intellij.grazie.text.CheckerRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextExtractor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Times a full check (every text checker, as the inspection runs them) of a few representative texts. Not a pass/fail test:
 * run it with -PgrazieBenchmark=true and read the numbers in the test output.
 */
class CheckingBenchmarkTest : BasePlatformTestCase() {
  // {n} differs in every run, so that no sentence is answered from LanguageTool's result cache
  private val samples = listOf(
    Triple("comment", "A.java", """
      class A {
        // This method returns the the number of item in cache {n}, it dont count the expired entries.
        // When a entry is removed from table {n} we recompute the total so callers should not relies on the cached value.
        int size() { return 0; }
      }
    """.trimIndent()),
    Triple("markdown", "README.md", """
      # Installation

      To install version {n} of the plugin, open the settings and choose the plugins page. Their are {n} options available,
      and you can allways change them later. This plugin support English and {n} other language, which is checked locally
      in the IDE without sending any text to a server.
    """.trimIndent()),
    Triple("commit message", "COMMIT.txt", """
      Fix the crash {n} when user open a empty project

      The indexer tried to read file {n} that was already deleted, which lead to an exception. Now we checks that the
      file {n} still exist before reading it.
    """.trimIndent()),
  )

  fun testTiming() {
    if (System.getProperty("grazie.benchmark") != "true") return
    val runtime = Runtime.getRuntime()
    System.gc()
    val before = runtime.totalMemory() - runtime.freeMemory()
    for ((label, name, text) in samples) {
      val first = measure(name, text, 0)
      val runs = 20
      val times = (1..runs).map { measure(name, text, it).first }.sorted()
      println("BENCHMARK $label: first=${first.first}ms median=${times[runs / 2]}ms min=${times.first()}ms problems=${measure(name, text, 99).second}")
    }
    System.gc()
    val after = runtime.totalMemory() - runtime.freeMemory()
    println("BENCHMARK heap growth after checking: ${(after - before) / (1024 * 1024)} MB")
  }

  private fun measure(name: String, text: String, i: Int): Pair<Long, Int> {
    val texts = extract(name, text, i)
    if (i == 99) {
      val problems = ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
        ProgressManager.getInstance().runProcess<List<String>>({
          runReadAction {
            texts.flatMap { t -> CheckerRunner(t).run().map { "${it.rule.globalId} '${it.highlightRanges[0].subSequence(t)}'" } }
          }
        }, EmptyProgressIndicator())
      }.get()
      problems.forEach { println("BENCHMARK   $name: $it") }
    }
    val start = System.nanoTime()
    // as the inspection runs: on a background thread, in a read action
    val count = ApplicationManager.getApplication().executeOnPooledThread<Int> {
      ProgressManager.getInstance().runProcess<Int>({
        runReadAction { texts.sumOf { CheckerRunner(it).run().size } }
      }, EmptyProgressIndicator())
    }.get()
    return Pair((System.nanoTime() - start) / 1_000_000, count)
  }

  private fun extract(name: String, text: String, i: Int) = run {
    val file: PsiFile = myFixture.configureByText("${i}_$name", text.replace("{n}", (100 + i).toString()))
    runReadAction {
      PsiTreeUtil.collectElements(file) { it.firstChild == null }
        .flatMap { leaf: PsiElement -> TextExtractor.findTextsAt(leaf, TextDomain.ALL) }
        .distinct()
    }
  }
}
