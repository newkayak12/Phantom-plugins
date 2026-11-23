// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties;

import com.intellij.lang.properties.codeInspection.duplicatePropertyInspection.DuplicatePropertyInspection;
import com.intellij.testFramework.JavaInspectionTestCase;
import org.jetbrains.annotations.NotNull;

public class DuplicatePropertyInspectionTest extends JavaInspectionTestCase {
  //ProblemDescriptor.getLineNumber()==1 for this inspection (there is no RefPropertyElement thus PsiElement -> PsiFile)
  private DuplicatePropertyInspection myTool;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myTool = new DuplicatePropertyInspection();
  }

  private void doTest() {
    doTest("duplicateProperty/" + getTestName(true), myTool);
  }

  public void testDuplicateValues() {
    doTest();
  }

  public void testDuplicateValuesCurrentFileAnalysis() {
    doTest();
  }

  public void testDuplicateValuesInDifferentFiles() {
    myTool.CURRENT_FILE = false;
    myTool.MODULE_WITH_DEPENDENCIES = true;
    doTest();
  }

  public void testDuplicateKeysWithDifferentValues() {
    myTool.CURRENT_FILE = false;
    myTool.MODULE_WITH_DEPENDENCIES = true;
    myTool.CHECK_DUPLICATE_KEYS = false;
    myTool.CHECK_DUPLICATE_VALUES = false;
    myTool.CHECK_DUPLICATE_KEYS_WITH_DIFFERENT_VALUES = true;
    doTest();
  }

  public void testDuplicateKeys() {
    myTool.CURRENT_FILE = false;
    myTool.MODULE_WITH_DEPENDENCIES = true;
    myTool.CHECK_DUPLICATE_KEYS = true;
    myTool.CHECK_DUPLICATE_VALUES = false;
    myTool.CHECK_DUPLICATE_KEYS_WITH_DIFFERENT_VALUES = false;
    doTest();
  }

  public void testDuplicateKeysWithAndWithoutDifferent() {
    myTool.CURRENT_FILE = false;
    myTool.MODULE_WITH_DEPENDENCIES = true;
    myTool.CHECK_DUPLICATE_KEYS = true;
    myTool.CHECK_DUPLICATE_VALUES = false;
    myTool.CHECK_DUPLICATE_KEYS_WITH_DIFFERENT_VALUES = true;
    doTest();
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData";
  }
}
