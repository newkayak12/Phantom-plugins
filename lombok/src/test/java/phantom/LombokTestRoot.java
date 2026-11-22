package de.plushnikov.intellij.plugin;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.util.lang.JavaVersion;

import java.io.File;

/** Phantom-plugins: the tests run in the plugin's folder (testData/ there), not in the IntelliJ monorepo. */
public final class LombokTestRoot {
  public static final String PATH = new File("").getAbsolutePath().replace(File.separatorChar, '/');
  private static final String MOCK_JDK_HOME = System.getProperty("phantom.mockjdk.home") + "/java";

  /** IdeaTestUtil.getMockJdk18 reads community/java/mockJDK-1.8 under the IDE home; Gradle downloads it (MockJdks.kt). */
  public static Sdk mockJdk18() {
    return IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(8)), MOCK_JDK_HOME + "/mockJDK-1.8");
  }

  /** JAVA_LATEST's mockJDK-1.7, also downloaded by Gradle. */
  public static final com.intellij.testFramework.LightProjectDescriptor JAVA_LATEST =
    new com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(com.intellij.pom.java.LanguageLevel.HIGHEST) {
      @Override
      public Sdk getSdk() {
        return IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), MOCK_JDK_HOME + "/mockJDK-1.7");
      }
    };

  private LombokTestRoot() { }
}
