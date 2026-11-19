// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.psi.PsiMethod
import java.nio.file.Files
import java.nio.file.Path

/** Needs the Kotlin plugin; excluded in build.gradle.kts when the IDE does not bundle it. */
class LocalIndexKotlinTest : CachedCheckoutTestCase() {
  override fun writeCheckout(root: Path) {
    CheckoutFixture.writeJavaCheckout(root)
    Files.writeString(root.resolve("src/p/Util.kt"), "package p\n\nfun shout(s: String) = s.uppercase()\n")
    val main = root.resolve(CheckoutFixture.MAIN)
    Files.writeString(main, Files.readString(main).replace("String s = ", "String t = UtilKt.shout(\"x\");\n    String s = "))
  }

  fun `test every cached kotlin index has a hit for the kt file`() {
    val kotlin = trace.filter { it.path.endsWith(".kt") }
    val ids = listOf("Stubs") + LocalIndexAllowlist.CONTENT_ONLY.filter { it.contains("Kotlin") }
    for (id in ids) {
      val events = kotlin.filter { it.index == id }
      assertEquals("$id $events", 1, events.count { it.hit })
      assertEquals("$id $events", 0, events.count { !it.hit })
    }
  }

  fun `test java to kotlin navigation stays in this checkout`() {
    myFixture.configureFromTempProjectFile(CheckoutFixture.MAIN)
    myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("shout(") + 1)
    val file = (myFixture.elementAtCaret as PsiMethod).navigationElement.containingFile.virtualFile
    assertTrue(file.path, file.path.startsWith(myFixture.tempDirPath))
    assertTrue(file.path.endsWith("src/p/Util.kt"))
  }
}
