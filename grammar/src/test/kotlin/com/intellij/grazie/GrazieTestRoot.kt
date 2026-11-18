package com.intellij.grazie

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.lang.JavaVersion
import java.io.File

/**
 * Phantom-plugins: LightJavaCodeInsightFixtureTestCase.JAVA_LATEST reads community/java/mockJDK-1.7 under the IDE home,
 * which the tests here don't have; a copy sits in testData, where Gradle runs the tests.
 */
object GrazieTestRoot {
  @JvmField
  val JAVA_LATEST: LightProjectDescriptor = object : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
    override fun getSdk(): Sdk =
      IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), File("testData/mockJDK-1.7").absolutePath)
  }
}
