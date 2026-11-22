// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.theoryinpractice.testng.configuration;

import com.intellij.codeInsight.TestFrameworks;
import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.junit.jupiter.api.Assertions;

import java.util.List;

public class TestNGRunMarkerTest extends LightJavaCodeInsightFixtureTestCase {
  // Phantom-plugins: mock JDK 1.7 from testData, not from the IDE home
  @Override
  protected @org.jetbrains.annotations.NotNull com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
    return com.theoryinpractice.testng.TestNGTestRoot.JAVA_LATEST;
  }


  @Override
  public void setUp() throws Exception {
    super.setUp();
    myFixture.addClass("package org.testng.annotations; @interface Test {String[] dependsOnMethods() default {};}");
  }

  public void testSimpleRunMarker() {
    myFixture.configureByText(
      "SomethingTest.java",
      """
        package org.example;
        
        import org.testng.annotations.Test;

        public class SomethingTest {
        
        
            private int number = 1;
        
            @Test
            public void givenNumber_whenEven_thenTrue() {
            }
        }
        """);
    List<GutterMark> gutters = myFixture.findAllGutters();
    Assertions.assertEquals(2, gutters.size());
  }

  public void testSimpleRunMarkerInDumbMode() {
    PsiJavaFile file = (PsiJavaFile)myFixture.configureByText(
      "SomethingTest.java",
      """
        package org.example;
        
        import org.testng.annotations.Test;

        public class SomethingTest {
        
        
            private int number = 1;
        
            @Test
            public void givenNumber_whenEven_thenTrue() {
            }
        }
        """);

    PsiClass aClass = file.getClasses()[0];
    PsiMethod method = aClass.getMethods()[0];
    DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
      Assertions.assertTrue(TestFrameworks.getInstance().isTestClass(aClass));
      Assertions.assertTrue(TestFrameworks.getInstance().isTestMethod(method));
    });
  }
}
