// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.sh.editor;

import com.intellij.openapi.application.PluginPathManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

public class ShFoldingTest extends BasePlatformTestCase {

  @NotNull
  @Override
  protected String getTestDataPath() {
    return com.intellij.sh.ShTestRoot.PATH + "/testData/folding/";
  }

  public void testFunction() { doTest(); }
  public void testHeredoc()  { doTest(); }
  public void testDoBlock()  { doTest(); }
  public void testComment()  { doTest(); }
  public void testShebang()  { doTest(); }

  private void doTest() {
    myFixture.testFoldingWithCollapseStatus(getTestDataPath() + "/" + getTestName(true) + ".sh");
  }
}