// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.theoryinpractice.testng.referenceContributor;

import com.intellij.codeInsight.daemon.impl.analysis.XmlPathReferenceInspection;
import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.ui.UIUtil;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class TestNGSuiteTest extends LightJavaCodeInsightFixtureTestCase {
  // Phantom-plugins: mock JDK 1.7 from testData, not from the IDE home
  @Override
  protected @org.jetbrains.annotations.NotNull com.intellij.testFramework.LightProjectDescriptor getProjectDescriptor() {
    return com.theoryinpractice.testng.TestNGTestRoot.JAVA_LATEST;
  }

  @BeforeMethod
  @Override
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

  @AfterMethod
  @Override
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

  public void testNothing(){}

  public void testTestNGSuiteFile() {
    UIUtil.invokeAndWaitIfNeeded(() -> {
      try {
        myFixture.addClass("package org.testng.annotations; public @interface DataProvider {}");
        myFixture.addClass("package org.testng.annotations; public @interface Test {}");
        myFixture.addClass("package o; @Test public class MyTest { public void testMe(){} }");
        myFixture.addFileToProject("subPack/test-unit.xml", "<suite>" +
                                                            "<test>" +
                                                            "<classes></classes>" +
                                                            "</test>" +
                                                            "</suite>");
        myFixture.enableInspections(new XmlPathReferenceInspection(), new XmlUnresolvedReferenceInspection());
        myFixture.testHighlighting("testng.xml");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  protected String getTestDataPath() {
    return com.theoryinpractice.testng.TestNGTestRoot.PATH + "/testData/references";
  }


}
