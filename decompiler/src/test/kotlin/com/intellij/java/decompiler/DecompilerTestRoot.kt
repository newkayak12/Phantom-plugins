package com.intellij.java.decompiler

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.lang.JavaVersion
import java.io.File

/**
 * Phantom-plugins: the tests run in the plugin's folder, not in the IntelliJ monorepo, so the mock JDKs and the Java
 * test data the IntelliJ test framework reads under the IDE home are copied into testData.
 */
internal object DecompilerTestRoot {
  val PATH: String = File("").absolutePath
  val MOCK_JDK_18: String = "$PATH/testData/mockJDK-1.8"

  /** JAVA_LATEST, with its mockJDK-1.7 from testData */
  val JAVA_LATEST: LightProjectDescriptor = object : LightJavaCodeInsightFixtureTestCase.ProjectDescriptor(LanguageLevel.HIGHEST) {
    override fun getSdk(): Sdk = IdeaTestUtil.createMockJdk(IdeaTestUtil.getMockJdkName(JavaVersion.compose(7)), "$PATH/testData/mockJDK-1.7")
  }
}
