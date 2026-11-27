// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FileKindNamesTest : LightJavaCodeInsightFixtureTestCase() {
  fun testEdtCallDoesNotParseAndFillsCacheInBackground() {
    val file = myFixture.addFileToProject("Foo.java", "interface Foo {}").virtualFile
    assertTrue(ApplicationManager.getApplication().isDispatchThread)
    var computedOnEdt = false
    val compute: (com.intellij.psi.PsiFile) -> String? = {
      if (ApplicationManager.getApplication().isDispatchThread) computedOnEdt = true
      JavaKindIcons.fileIconName(it)
    }
    assertNull(FileKindNames.name(file, project, compute))
    val deadline = System.currentTimeMillis() + 10_000
    var name: String? = null
    while (name == null && System.currentTimeMillis() < deadline) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      name = FileKindNames.name(file, project, compute)
    }
    assertEquals(JavaKindIcons.fileIconName(myFixture.psiManager.findFile(file)!!), name)
    assertFalse(computedOnEdt)
  }

  fun testBackgroundCallComputesDirectly() {
    val file = myFixture.addFileToProject("Bar.java", "enum Bar {}").virtualFile
    val name = ApplicationManager.getApplication().executeOnPooledThread<String?> {
      FileKindNames.name(file, project, JavaKindIcons::fileIconName)
    }.get()
    assertEquals(JavaKindIcons.fileIconName(myFixture.psiManager.findFile(file)!!), name)
  }
}
