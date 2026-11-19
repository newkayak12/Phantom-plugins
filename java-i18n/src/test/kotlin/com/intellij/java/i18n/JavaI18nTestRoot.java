package com.intellij.java.i18n;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.lang.JavaVersion;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class JavaI18nTestRoot {
  public static final String PATH = new File("").getAbsolutePath().replace(File.separatorChar, '/');

  /** IdeaTestUtil's mock JDKs 1.7 and 1.8 are community/java/mockJDK-1.x under the IDE home; copies sit in testData. */
  public static final String MOCK_JDK_17 = PATH + "/testData/mockJDK-1.7";
  public static final String MOCK_JDK_18 = PATH + "/testData/mockJDK-1.8";

  /** LightJavaCodeInsightFixtureTestCase.JAVA_8 on the mock JDK 1.8 from testData. */
  public static final LightProjectDescriptor JAVA_8 =
    new LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.JDK_1_8) {
      @Override
      public Sdk getSdk() {
        return IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(8)), MOCK_JDK_18);
      }
    };

  /** LightJavaCodeInsightFixtureTestCase.JAVA_LATEST (the default): the highest language level on mock JDK 1.7. */
  public static final LightProjectDescriptor JAVA_LATEST =
    new LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
      @Override
      public Sdk getSdk() {
        return mockJdk17();
      }
    };

  public static Sdk mockJdk17() {
    return IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), MOCK_JDK_17);
  }

  private JavaI18nTestRoot() { }
}
