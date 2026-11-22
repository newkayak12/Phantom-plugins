// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.java.lomboktest;

import com.intellij.codeInspection.localCanBeFinal.LocalCanBeFinal;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import de.plushnikov.intellij.plugin.LombokTestUtil;
import org.jetbrains.annotations.NotNull;


public class LombokLocalCanBeFinalTest extends LightJavaCodeInsightFixtureTestCase {
  // Phantom-plugins: test data under the plugin's folder, not the IDE home
  @Override
  protected String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH + getBasePath();
  }

  private LocalCanBeFinal myTool;

  @Override
  protected String getBasePath() {
    return "/testData/inspection/localCanBeFinal";
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return LombokTestUtil.LOMBOK_JAVA21_DESCRIPTOR;
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myTool = new LocalCanBeFinal();
  }

  private void doTest() {
    myFixture.enableInspections(myTool);
    myFixture.testHighlighting(true, false, false, getTestName(false) + ".java");
  }

  public void testLombokVal() {
    myTool.REPORT_PARAMETERS = true;
    myTool.REPORT_VARIABLES = true;
    myTool.REPORT_IMPLICIT_FINALS = true;
    doTest();
  }
}

