// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.

package com.theoryinpractice.testng.inspection;

import com.intellij.testFramework.JavaInspectionTestCase;
import com.intellij.util.ui.UIUtil;
import com.theoryinpractice.testng.util.TestNGUtil;
import org.jetbrains.annotations.NonNls;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class UndeclaredTestsInspectionTest extends JavaInspectionTestCase {

  @Override
  public String getName() {
    return "test";
  }

  @Override
  @BeforeMethod
  protected void setUp() {
    UIUtil.invokeAndWaitIfNeeded(() -> {
      try {
        super.setUp();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  @AfterMethod
  protected void tearDown() {
    UIUtil.invokeAndWaitIfNeeded(() -> {
      try {
        super.tearDown();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  // Phantom-plugins: JavaInspectionTestCase's descriptor (ext_src and test_src roots) on mock JDK 1.7 from testData, not
  // from the IDE home
  private static final com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor DESCRIPTOR =
    new com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor(
      () -> com.intellij.testFramework.IdeaTestUtil.createMockJdk("java 1.7", com.theoryinpractice.testng.TestNGTestRoot.MOCK_JDK_17)) {
      @Override
      public void configureModule(@org.jetbrains.annotations.NotNull com.intellij.openapi.module.Module module,
                                  @org.jetbrains.annotations.NotNull com.intellij.openapi.roots.ModifiableRootModel model,
                                  @org.jetbrains.annotations.NotNull com.intellij.openapi.roots.ContentEntry contentEntry) {
        super.configureModule(module, model, contentEntry);
        contentEntry.addSourceFolder(contentEntry.getUrl() + "/ext_src", false);
        contentEntry.addSourceFolder(contentEntry.getUrl() + "/test_src", true);
      }
    };

  @Override
  protected @org.jetbrains.annotations.NotNull com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
    return DESCRIPTOR;
  }

  @Override
  @NonNls
  protected String getTestDataPath() {
    return com.theoryinpractice.testng.TestNGTestRoot.PATH + "/testData/inspection";
  }

  @DataProvider
  public Object[][] data() {
    return new Object[][]{{"declared"}, {"undeclared"}, {"packageDeclared"}, {"inSubPackage"}, {"incorrectSubPackage"}, {"packageNonDeclared"}, {"commented"}, {"commented1"}};
  }

  @Test(dataProvider = "data")
  public void doTest(final String name) {
    UIUtil.invokeAndWaitIfNeeded(() -> {
      try {
        TestNGUtil.hasDocTagsSupport = true;
        doTest("undeclaredTests/" + name, new UndeclaredTestInspection());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * @see junit.framework.TestSuite warning
   */
  public void test() {}
}
