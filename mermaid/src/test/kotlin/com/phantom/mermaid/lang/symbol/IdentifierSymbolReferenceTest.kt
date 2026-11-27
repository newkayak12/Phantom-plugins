// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.symbol

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.phantom.mermaid.lang.psi.symbol.identifier.IdentifierSymbol
import com.phantom.mermaid.lang.psi.symbol.identifier.IdentifierSymbolReference

class IdentifierSymbolReferenceTest : BasePlatformTestCase() {
  fun `test completion offers each identifier once`() {
    myFixture.configureByText("a.mermaid", SOURCE)
    val reference = IdentifierSymbolReference(myFixture.file.firstChild, TextRange(0, 1), "a")
    val names = reference.completionVariants.map { it.lookupString }
    assertTrue(names.toString(), "name" in names)
    assertEquals(names.distinct(), names)
  }

  fun `test resolve follows edits after the declarations were cached`() {
    myFixture.configureByText("a.mermaid", SOURCE)
    val reference = IdentifierSymbolReference(myFixture.file.firstChild, TextRange(0, 1), "name")
    assertEquals(2, reference.resolveReference().filterIsInstance<IdentifierSymbol>().size)

    val document = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project) {
      val start = document.text.lastIndexOf("  subgraph name")
      document.deleteString(start, document.textLength)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val fresh = IdentifierSymbolReference(myFixture.file.firstChild, TextRange(0, 1), "name")
    assertEquals(1, fresh.resolveReference().filterIsInstance<IdentifierSymbol>().size)
  }

  private companion object {
    const val SOURCE = "flowchart\n  subgraph name\n      a --> b\n  end\n  a --> c\n  subgraph name\n      c --> d\n  end\n"
  }
}
