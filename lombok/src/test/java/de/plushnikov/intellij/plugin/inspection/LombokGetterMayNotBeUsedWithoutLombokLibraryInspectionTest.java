// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package de.plushnikov.intellij.plugin.inspection;

import com.intellij.codeInsight.daemon.LightDaemonAnalyzerTestCase;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NotNull;

public class LombokGetterMayNotBeUsedWithoutLombokLibraryInspectionTest extends LightDaemonAnalyzerTestCase {
  @Override
  protected LocalInspectionTool @NotNull [] configureLocalInspectionTools() {
    return new LocalInspectionTool[]{new LombokGetterMayBeUsedInspection()};
  }

  @Override
  protected @NotNull String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH + "/testData";
  }
  @NotNull
  private String getFilePath() {
    return "/inspection/lombokGetterMayBeUsed/" + getTestName(false) + ".java";
  }

  private void doTest() {
    doTest(getFilePath(), true, false);
  }

  public void testNoLibrary() {
    doTest();
  }
}
