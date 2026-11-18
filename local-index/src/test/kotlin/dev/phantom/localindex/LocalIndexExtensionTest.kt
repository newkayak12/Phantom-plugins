// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LocalIndexExtensionTest : BasePlatformTestCase() {
  fun `test extension is registered and does not version indexes`() {
    val ext = LocalIndexExtension.getInstance()
    assertEquals(-1, ext.version)
    assertEquals("phantom-local-index", ext.persistentStateRoot)
    assertNull(ext.createFileIndexingStatusProcessor(project))
  }
}
