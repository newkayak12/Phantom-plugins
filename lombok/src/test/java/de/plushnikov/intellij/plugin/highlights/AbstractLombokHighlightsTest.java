package de.plushnikov.intellij.plugin.highlights;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.testFramework.LightProjectDescriptor;
import com.siyeh.ig.LightJavaInspectionTestCase;
import de.plushnikov.intellij.plugin.LombokTestUtil;
import de.plushnikov.intellij.plugin.inspection.LombokInspection;
import org.jetbrains.annotations.NotNull;


/**
 * @author Lekanich
 */
public abstract class AbstractLombokHighlightsTest extends LightJavaInspectionTestCase {
  // Phantom-plugins: test data under the plugin's folder, not the IDE home
  @Override
  protected String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH + getBasePath();
  }


  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return LombokTestUtil.LOMBOK_JAVA21_DESCRIPTOR;
  }

  @Override
  protected String getBasePath() {
    return "/testData/highlights";
  }

  @Override
  protected InspectionProfileEntry getInspection() {
    return new LombokInspection();
  }
}

