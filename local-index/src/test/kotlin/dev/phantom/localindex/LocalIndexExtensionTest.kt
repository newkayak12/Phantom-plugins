// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndexTumbler
import dev.phantom.localindex.store.LocalIndexStore
import dev.phantom.localindex.store.StoreHeader

class LocalIndexExtensionTest : BasePlatformTestCase() {
  fun `test extension is registered and does not version indexes`() {
    val ext = LocalIndexExtension.getInstance()
    assertEquals(-1, ext.version)
    assertEquals("phantom-local-index", ext.persistentStateRoot)
    assertNull(ext.createFileIndexingStatusProcessor(project))
  }

  fun `test resetting the stub index right after initialize empties entries of the previous session`() {
    val ext = LocalIndexExtension.getInstance()
    val header = StoreHeader(LocalIndexStore.FORMAT_VERSION, 1, 1, "", 0)
    val key = ByteArray(20) { it.toByte() }
    val tumbler = FileBasedIndexTumbler("LocalIndexExtensionTest")
    tumbler.turnOff() // shuts the extension down; turnOn below initializes it and rebuilds the wrapped indexes
    try {
      ext.initialize(null)
      ext.store!!.run {
        ensureHeader("Stubs", header)
        put("Stubs", key, 1, byteArrayOf(1, 0))
        awaitIdle()
      }
      ext.shutdown()
      ext.initialize(null) // the scan of the previous session may not have registered Stubs yet
      ext.resetPersistentState(StubUpdatingIndex.INDEX_ID)
      val store = ext.store!!
      store.ensureHeader("Stubs", header) // what the retried combineIndex does
      store.awaitIdle()
      assertNull(store.get("Stubs", key))
    }
    finally {
      ext.shutdown()
      tumbler.turnOn()
    }
  }
}
