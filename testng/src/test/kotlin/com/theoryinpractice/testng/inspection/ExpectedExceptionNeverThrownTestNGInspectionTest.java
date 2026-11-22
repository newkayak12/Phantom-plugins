// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.theoryinpractice.testng.inspection;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * @author Bas Leijdekkers
 */
public class ExpectedExceptionNeverThrownTestNGInspectionTest extends LightJavaCodeInsightFixtureTestCase {
  // Phantom-plugins: mock JDK 1.7 from testData, not from the IDE home
  @Override
  protected @org.jetbrains.annotations.NotNull com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
    return com.theoryinpractice.testng.TestNGTestRoot.JAVA_LATEST;
  }


  public void testSimple() {
    myFixture.testHighlighting(true, false, false, "Simple.java");
  }

  public void testArrayInitializerMemberValue() {
    myFixture.testHighlighting(true, false, false, "ArrayInitializerMemberValue.java");
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return com.theoryinpractice.testng.TestNGTestRoot.PATH + "/testData/inspection/expected_exception_never_thrown/";
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myFixture.addClass("""
                         package org.testng.annotations;
                         public @interface Test {
                           Class[] expectedExceptions() default {};
                         }""");
    myFixture.enableInspections(new ExpectedExceptionNeverThrownTestNGInspection());
  }
}
