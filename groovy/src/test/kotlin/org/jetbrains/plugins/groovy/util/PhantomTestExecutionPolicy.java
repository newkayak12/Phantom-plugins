package org.jetbrains.plugins.groovy.util;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.IdeaTestExecutionPolicy;

import java.io.File;

/**
 * Phantom-plugins: the test framework takes the mock JDKs 1.7 and 1.8 (IdeaTestUtil.getMockJdk17/18, the default SDK of
 * light test projects) from java/ under the IntelliJ monorepo's community root, which it finds through the test home
 * (PlatformTestUtil.getCommunityPath). The tests run against an installed IDE, so the home they see is testData/ideaHome,
 * where copies of community/java/mockJDK-1.7 and mockJDK-1.8 sit. Set by build.gradle.kts (idea.test.execution.policy).
 */
public final class PhantomTestExecutionPolicy extends IdeaTestExecutionPolicy {
  @Override
  protected String getName() {
    return "Phantom-plugins";
  }

  @Override
  public String getHomePath() {
    return new File("testData/ideaHome").getAbsolutePath();
  }

  // The fixtures call this only with a policy set, sometimes without a file (GroovyCopyClassTest); nothing to do here
  @Override
  public void beforeCheckResult(PsiFile file) {
  }
}
