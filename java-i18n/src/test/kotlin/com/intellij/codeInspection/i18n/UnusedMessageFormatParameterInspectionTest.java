package com.intellij.codeInspection.i18n;

import com.intellij.lang.properties.UnusedMessageFormatParameterInspection;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class UnusedMessageFormatParameterInspectionTest extends LightJavaCodeInsightFixtureTestCase {

  private void doTest() {
    myFixture.enableInspections(new UnusedMessageFormatParameterInspection());
    myFixture.testHighlighting(getTestName(false) + ".properties");
  }
  public void testSimpleString() {
    doTest();
  }

  public void testCorruptedValue() {
    doTest();
  }

  public void testWithChoiceFormat() {
    doTest();
  }

  public void testRegexp() {
    doTest();
  }

  @Override
  protected String getTestDataPath() {
    return com.intellij.java.i18n.JavaI18nTestRoot.PATH + "/testData/inspections/unusedParameter";
  }
}
