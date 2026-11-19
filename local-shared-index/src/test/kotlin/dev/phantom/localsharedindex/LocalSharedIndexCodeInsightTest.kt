// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.FunctionalExpressionSearch
import java.nio.file.Path

/** Highlighting, navigation, find usages, completion and functional expression search with index data from the cache. */
class LocalSharedIndexCodeInsightTest : CachedCheckoutTestCase() {
  override fun writeCheckout(root: Path) {
    CheckoutFixture.writeJavaCheckout(root)
  }

  private fun greetMethod(): PsiMethod =
    JavaPsiFacade.getInstance(project).findClass("p.Greeter", GlobalSearchScope.projectScope(project))!!.findMethodsByName("greet", false).single()

  fun `test index data came from the cache`() {
    val stubs = trace.filter { it.index == "Stubs" && "/src/" in it.path }
    // The fixture project is the only project open besides the seed, and its files are mapped once each (measured), unlike the
    // second of two checkouts opened by LocalSharedIndexReuseTest, which maps each Java file twice: 20 files, 20 hits.
    assertEquals(stubs.toString(), 20, stubs.count { it.hit })
    assertEquals(20, stubs.map { it.path }.toSet().size)
    assertEquals(0, stubs.count { !it.hit })
  }

  fun `test highlighting has no errors`() {
    myFixture.configureFromTempProjectFile(CheckoutFixture.MAIN)
    assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
  }

  fun `test navigation stays in this checkout`() {
    myFixture.configureFromTempProjectFile(CheckoutFixture.MAIN)
    val text = myFixture.editor.document.text
    myFixture.editor.caretModel.moveToOffset(text.indexOf("greet(\"x\")") + 1)
    val target = myFixture.elementAtCaret as PsiMethod
    assertEquals("greet", target.name)
    assertTrue(target.containingFile.virtualFile.path, target.containingFile.virtualFile.path.startsWith(myFixture.tempDirPath))
    assertTrue(target.containingFile.virtualFile.path.endsWith("src/p/Greeter.java"))
  }

  fun `test find usages`() {
    // Main.java calls greet three times (the lambda body, the null argument and the concatenation).
    assertEquals(3, myFixture.findUsages(greetMethod()).size)
  }

  fun `test completion sees cached classes`() {
    myFixture.configureFromTempProjectFile(CheckoutFixture.MAIN)
    val document = myFixture.editor.document
    val offset = document.text.indexOf("g.greet(null);")
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(offset, "Gree")
      myFixture.editor.caretModel.moveToOffset(offset + 4)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val lookups = myFixture.completeBasic()
    // One match is inserted without showing a list.
    if (lookups == null) assertTrue(document.text, "Greeter" in document.text.substring(offset, offset + 8))
    else assertTrue(myFixture.lookupElementStrings.toString(), "Greeter" in myFixture.lookupElementStrings!!)
  }

  fun `test functional expression search`() {
    val runnable = JavaPsiFacade.getInstance(project).findClass("java.lang.Runnable", GlobalSearchScope.allScope(project))!!
    assertEquals(1, FunctionalExpressionSearch.search(runnable, GlobalSearchScope.projectScope(project)).findAll().size)
  }
}
