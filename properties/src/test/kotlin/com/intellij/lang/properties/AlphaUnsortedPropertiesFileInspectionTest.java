// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.daemon.quickFix.LightQuickFixParameterizedTestCase;
import com.intellij.lang.properties.codeInspection.unsorted.AlphaUnsortedPropertiesFileInspection;
import com.intellij.lang.properties.psi.codeStyle.PropertiesCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class AlphaUnsortedPropertiesFileInspectionTest extends LightQuickFixParameterizedTestCase {
  // Phantom-plugins: this data is java/java-tests/testData/properties in IntelliJ, copied to testData/properties
  @Override
  protected @NotNull String getTestDataPath() {
    return com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData";
  }

  @Override
  protected @NonNls String getBasePath() {
    return "/properties/alphaunsorted";
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    enableInspectionTool(new AlphaUnsortedPropertiesFileInspection());

    final CodeStyleSettings codeStyleSettings = CodeStyle.getSettings(getProject());
    final PropertiesCodeStyleSettings propertiesSettings = codeStyleSettings.getCustomSettings(PropertiesCodeStyleSettings.class);

    propertiesSettings.KEEP_BLANK_LINES = true;
  }
}
