package de.plushnikov.intellij.plugin;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractLombokLightCodeInsightTestCase extends LightJavaCodeInsightFixtureTestCase {
  // Phantom-plugins: test data under the plugin's folder, not the IDE home
  @Override
  protected String getTestDataPath() {
    return de.plushnikov.intellij.plugin.LombokTestRoot.PATH + getBasePath();
  }


  @Override
  protected String getBasePath() {
    return "/testData";
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return LombokTestUtil.LOMBOK_JAVA21_DESCRIPTOR;
  }

  protected PsiFile loadToPsiFile(String fileName) {
    VirtualFile virtualFile = myFixture.copyFileToProject(fileName, fileName);
    myFixture.configureFromExistingVirtualFile(virtualFile);
    return myFixture.getFile();
  }
}
