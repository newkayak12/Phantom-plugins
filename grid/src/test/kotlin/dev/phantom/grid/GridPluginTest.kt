// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grid

import com.intellij.database.csv.CsvFileType
import com.intellij.database.editor.CsvTableFileEditorProvider
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The embedded grid modules load from the one jar: their file type and editor are registered under Phantom's id. */
class GridPluginTest : BasePlatformTestCase() {
  fun testPluginLoadsUnderPhantomId() {
    val plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.grid"))
    assertNotNull(plugin)
    assertTrue(plugin!!.isEnabled)
  }

  fun testCsvAndTsvFilesAreCsvFileType() {
    val manager = FileTypeManager.getInstance()
    assertSame(CsvFileType.INSTANCE, manager.getFileTypeByFileName("data.csv"))
    assertSame(CsvFileType.INSTANCE, manager.getFileTypeByFileName("data.tsv"))
  }

  fun testCsvFileOpensInTheDataEditor() {
    val file = myFixture.configureByText("people.csv", "name,age\nAda,36\n").virtualFile
    val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
    assertTrue(providers.any { it is CsvTableFileEditorProvider })
  }
}
