// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.ID

class LocalIndexAllowlistTest : LightJavaCodeInsightFixtureTestCase() {
  private fun extensionNamed(id: String): FileBasedIndexExtension<*, *> =
    FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.single { it.name.name == id }

  fun `test content-only indexes are accepted`() {
    for (id in listOf("IdIndex", "java.fun.expression", "java.null.method.argument", "java.binary.plus.expression", "Trigram.Index", "Stubs")) {
      assertTrue(id, LocalIndexAllowlist.accepts(extensionNamed(id)))
    }
  }

  fun `test settings-dependent and content-less indexes are refused`() {
    for (id in listOf("TodoIndex", "FilenameIndex", "filetypes")) {
      assertFalse(id, LocalIndexAllowlist.accepts(extensionNamed(id)))
    }
  }

  fun `test stub index keys are never wrapped`() {
    val key = StubIndexKey.createIndexKey<String, com.intellij.psi.PsiElement>("dev.phantom.localindex.test.stub.key")
    val extension = object : FileBasedIndexExtension<String, Void>() {
      @Suppress("UNCHECKED_CAST")
      override fun getName(): ID<String, Void> = key as ID<String, Void>
      override fun getIndexer() = com.intellij.util.indexing.DataIndexer<String, Void, com.intellij.util.indexing.FileContent> { emptyMap() }
      override fun getKeyDescriptor() = com.intellij.util.io.EnumeratorStringDescriptor.INSTANCE
      override fun getValueExternalizer() = com.intellij.util.io.VoidDataExternalizer.INSTANCE
      override fun getVersion() = 0
      override fun getInputFilter() = com.intellij.util.indexing.FileBasedIndex.InputFilter { true }
      override fun dependsOnFileContent() = true
    }
    assertFalse(LocalIndexAllowlist.accepts(extension))
  }

  fun `test stubs cached only for flavored file types`() {
    assertTrue(LocalIndexAllowlist.cachesStubsFor(JavaFileType.INSTANCE))
    assertFalse(LocalIndexAllowlist.cachesStubsFor(XmlFileType.INSTANCE))
    assertFalse(LocalIndexAllowlist.cachesStubsFor(PlainTextFileType.INSTANCE))
  }
}
