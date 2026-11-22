/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.spellchecker.inspection;

import com.intellij.codeInsight.daemon.quickFix.LightQuickFixTestCase;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.grazie.spellchecker.inspection.SpellcheckerInspectionTestCase;
import org.jetbrains.annotations.NotNull;

public class JavaSpellcheckerSuppressionTest extends LightQuickFixTestCase {
  // Phantom-plugins: mock JDK from testData, not from the IDE home
  @Override
  protected com.intellij.openapi.projectRoots.Sdk getProjectJDK() {
    return com.intellij.java.i18n.JavaI18nTestRoot.mockJdk17();
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return com.intellij.java.i18n.JavaI18nTestRoot.PATH + "/testData/suppression";
  }

  public void testClassName() { doTest(); }
  public void testStringLiteral() { doTest(); }

  @Override
  protected LocalInspectionTool @NotNull [] configureLocalInspectionTools() {
    return SpellcheckerInspectionTestCase.getInspectionTools();
  }

  private void doTest() {
    doSingleTest(getTestName(false) + ".java");
  }
}
