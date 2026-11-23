// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties;

import com.intellij.codeInsight.daemon.quickFix.ActionHint;
import com.intellij.codeInsight.daemon.quickFix.LightQuickFixParameterizedTestCase;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class AddNewlineAbovePlaceCaretTest extends LightQuickFixParameterizedTestCase {

  // Phantom-plugins: this data is java/java-tests/testData/properties in IntelliJ, copied to testData/properties
  @Override
  protected @NotNull String getTestDataPath() {
    return com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData";
  }

  @Override
  protected @NonNls String getBasePath() {
    return "/properties/addnewline";
  }

  @Override
  protected void doAction(@NotNull ActionHint actionHint, @NotNull String testFullPath, @NotNull String testName) {
    executeAction("EditorStartNewLineBefore");

    checkResult(testName);
  }

  private void checkResult(@NotNull final String testName) {
    final String expectedFilePath = getBasePath() + "/after" + testName;
    checkResultByFile("In file: " + expectedFilePath, expectedFilePath, false);
  }
}
