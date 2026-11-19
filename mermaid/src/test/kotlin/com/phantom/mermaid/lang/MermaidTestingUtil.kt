package com.phantom.mermaid.lang

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.UsefulTestCase
import java.nio.file.Path
import kotlin.io.path.Path

object MermaidTestingUtil {
  val TEST_DATA_PATH: String
    get() = FileUtil.toSystemIndependentName(java.io.File("testData").absolutePath)

  fun obtainTestDataPath(): Path {
    return Path(TEST_DATA_PATH)
  }

  fun getTestName(name: String?, lowercaseFirstLetter: Boolean): String {
    val testName = UsefulTestCase.getTestName(name, lowercaseFirstLetter)
    return testName.trimStart().replace(' ', '_')
  }
}
