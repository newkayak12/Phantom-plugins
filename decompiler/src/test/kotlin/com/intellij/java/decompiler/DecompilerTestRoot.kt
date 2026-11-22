package com.intellij.java.decompiler

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.lang.JavaVersion
import java.io.File

/**
 * Phantom-plugins: the tests run in the plugin's folder, not in the IntelliJ monorepo, so the Java test data the IntelliJ
 * test framework reads under the IDE home is copied into testData. The mock JDKs come from Gradle (MockJdks.kt).
 */
internal object DecompilerTestRoot {
  val PATH: String = File("").absolutePath
  private val MOCK_JDK_HOME: String = System.getProperty("phantom.mockjdk.home") + "/java"
  val MOCK_JDK_18: String = "$MOCK_JDK_HOME/mockJDK-1.8"

  /** JAVA_LATEST, with its mockJDK-1.7 */
  val JAVA_LATEST: LightProjectDescriptor = object : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
    override fun getSdk(): Sdk = IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), "$MOCK_JDK_HOME/mockJDK-1.7")
  }
}
