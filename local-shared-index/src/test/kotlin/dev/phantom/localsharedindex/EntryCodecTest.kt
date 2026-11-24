// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.psi.impl.cache.impl.id.IdEntryToScopeMapImpl
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.psi.search.UsageSearchContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndexExtension

class EntryCodecTest : BasePlatformTestCase() {
  fun `test IdIndex data comes back as the indexer made it`() {
    @Suppress("UNCHECKED_CAST")
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.first { it.name == IdIndex.NAME } as FileBasedIndexExtension<IdIndexEntry, Int>
    val codec = EntryCodec.of(extension)
    val data = IdEntryToScopeMapImpl().apply {
      updateMask(IdIndexEntry("any", true).wordHashCode, UsageSearchContext.ANY.toInt())
      updateMask(IdIndexEntry("code", true).wordHashCode, UsageSearchContext.IN_CODE.toInt())
    }
    assertEquals(data, codec.decode(codec.encode(data), 1))
  }
}
