// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package de.plushnikov.intellij.plugin.inspection;

import com.intellij.testFramework.LightProjectDescriptor;
import com.siyeh.ig.LightJavaInspectionTestCase;
import de.plushnikov.intellij.plugin.LombokTestUtil;
import org.jetbrains.annotations.NotNull;

public abstract class LombokInspectionTest extends LightJavaInspectionTestCase {
  // Phantom-plugins: test data under the plugin's folder, not the IDE home
  @Override
  protected String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH + getBasePath();
  }

  static final String TEST_DATA_INSPECTION_DIRECTORY = "testData/inspection";

  @Override
  protected String getBasePath() {
    return "/";
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return LombokTestUtil.LOMBOK_JAVA21_DESCRIPTOR;
  }

  protected void configureAndTest(String text) {
    String className = getTestName(false);
    String fileName = className + ".java";

    myFixture.configureByText(fileName, text.formatted(className));
    myFixture.checkHighlighting();
  }
}
