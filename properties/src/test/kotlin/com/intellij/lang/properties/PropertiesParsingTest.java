// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties;

import com.intellij.lang.LanguageASTFactory;
import com.intellij.lang.properties.parsing.PropertiesParserDefinition;
import com.intellij.lang.properties.psi.impl.PropertiesASTFactory;
import com.intellij.testFramework.ParsingTestCase;

public class PropertiesParsingTest extends ParsingTestCase {

  public PropertiesParsingTest() {
    super("", "properties", new PropertiesParserDefinition());
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    addExplicitExtension(LanguageASTFactory.INSTANCE, PropertiesLanguage.INSTANCE, new PropertiesASTFactory());
  }

  @Override
  protected String getTestDataPath() {
    return com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData/propertiesFile/psi";
  }

  public void testProp1() { doTest(true); }
  public void testComments() { doTest(true); }
  public void testHeader() { doTest(true); }
}
