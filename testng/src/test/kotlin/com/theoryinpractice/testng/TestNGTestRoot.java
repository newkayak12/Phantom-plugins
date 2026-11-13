package com.theoryinpractice.testng;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.lang.JavaVersion;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class TestNGTestRoot {
  public static final String PATH = new File("").getAbsolutePath().replace(File.separatorChar, '/');

  /** IdeaTestUtil's mock JDK 1.7 is community/java/mockJDK-1.7 under the IDE home; a copy sits in testData. */
  public static final String MOCK_JDK_17 = PATH + "/testData/mockJDK-1.7";

  /** LightJavaCodeInsightFixtureTestCase.JAVA_LATEST (the default): the highest language level on mock JDK 1.7. */
  public static final LightProjectDescriptor JAVA_LATEST =
    new LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
      @Override
      public Sdk getSdk() {
        return IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), MOCK_JDK_17);
      }
    };

  private TestNGTestRoot() { }
}
