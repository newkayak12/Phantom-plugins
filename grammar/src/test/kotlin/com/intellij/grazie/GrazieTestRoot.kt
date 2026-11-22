package com.intellij.grazie

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.lang.JavaVersion

/**
 * Phantom-plugins: LightJavaCodeInsightFixtureTestCase.JAVA_LATEST reads community/java/mockJDK-1.7 under the IDE home,
 * which the tests here don't have; Gradle downloads it (MockJdks.kt) and passes its home in phantom.mockjdk.home.
 */
object GrazieTestRoot {
  @JvmField
  val JAVA_LATEST: LightProjectDescriptor = object : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
    override fun getSdk(): Sdk =
      IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), System.getProperty("phantom.mockjdk.home") + "/java/mockJDK-1.7")
  }
}
