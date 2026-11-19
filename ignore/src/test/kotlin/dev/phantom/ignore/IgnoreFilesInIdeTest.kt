// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore

import com.intellij.openapi.vcs.changes.ignore.psi.IgnoreFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ignore.lang.DockerIgnoreFileType
import dev.phantom.ignore.lang.HelmIgnoreFileType

class IgnoreFilesInIdeTest : BasePlatformTestCase() {
  fun `test ignore files get their file type and the ignore PSI`() {
    val docker = myFixture.configureByText(".dockerignore", "node_modules/\n# comment\n")
    assertEquals(DockerIgnoreFileType, docker.fileType)
    assertInstanceOf(docker, IgnoreFile::class.java)
    assertEquals(HelmIgnoreFileType, myFixture.configureByText(".helmignore", "*.tgz\n").fileType)
  }

  fun `test duplicate entries are reported`() {
    myFixture.configureByText(".npmignore", "dist/\n<warning>dist/</warning>\n")
    myFixture.enableInspections(com.intellij.openapi.vcs.changes.ignore.codeInspection.IgnoreFileDuplicateEntryInspection())
    myFixture.checkHighlighting()
  }
}
