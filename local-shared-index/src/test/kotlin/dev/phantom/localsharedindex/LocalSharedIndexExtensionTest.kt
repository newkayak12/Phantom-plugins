// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndexTumbler
import dev.phantom.localsharedindex.store.LocalSharedIndexStore
import dev.phantom.localsharedindex.store.StoreHeader

class LocalSharedIndexExtensionTest : BasePlatformTestCase() {
  fun `test extension is registered and does not version indexes`() {
    val ext = LocalSharedIndexExtension.getInstance()
    assertEquals(-1, ext.version)
    assertEquals("phantom-local-shared-index", ext.persistentStateRoot)
    // Skips files whose content another checkout indexed (#109).
    assertInstanceOf(ext.createFileIndexingStatusProcessor(project), LocalSharedIndexStatusProcessor::class.java)
  }

  fun `test resetting the stub index right after initialize empties entries of the previous session`() {
    val ext = LocalSharedIndexExtension.getInstance()
    val header = StoreHeader(LocalSharedIndexStore.FORMAT_VERSION, 1, 1, "", 0)
    val key = ByteArray(20) { it.toByte() }
    val tumbler = FileBasedIndexTumbler("LocalSharedIndexExtensionTest")
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
