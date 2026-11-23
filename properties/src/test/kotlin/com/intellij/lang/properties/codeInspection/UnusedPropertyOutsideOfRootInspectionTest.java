// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.lang.properties.codeInspection;

import com.intellij.lang.properties.codeInspection.unused.UnusedPropertyInspection;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.builders.ModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase;

import java.util.ArrayList;
import java.util.Arrays;

public class UnusedPropertyOutsideOfRootInspectionTest extends CodeInsightFixtureTestCase<ModuleFixtureBuilder<?>> {
  public void testUnusedOutsideOfSourceRoots() {
    myFixture.configureByFile("module/gradle.properties");
    myFixture.checkHighlighting();
  }

  public void testUnusedInsideOfSourceRoots() {
    myFixture.configureByFile("src/gradle.properties");
    myFixture.checkHighlighting();
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    // Phantom-plugins: testData in the plugin folder, not under the IDE home (getTestDataPath() is final here)
    myFixture.setTestDataPath(com.intellij.lang.properties.PropertiesTestRoot.PATH + "/testData/propertiesFile/unused");

    WriteAction.runAndWait(() -> {
      // "/" is not source root
      ModifiableRootModel model = ModuleRootManager.getInstance(myModule).getModifiableModel();
      ContentEntry contentEntry = model.getContentEntries()[0];
      for (SourceFolder folder : new ArrayList<>(Arrays.asList(contentEntry.getSourceFolders()))) {
        contentEntry.removeSourceFolder(folder);
      }
      model.commit();
    });

    PsiTestUtil.addSourceRoot(myModule, myFixture.getTempDirFixture().findOrCreateDir("src"));

    myFixture.enableInspections(UnusedPropertyInspection.class);
  }
}