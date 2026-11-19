// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.util.indexing.CompositeDataIndexer
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.IndexedHashesSupport
import java.security.MessageDigest

internal object CacheKeys {
  /**
   * The platform's indexed-file hash (content, charset, file name, file type, indexing flavor), mixed with the
   * sub-indexer version when the indexer is a [CompositeDataIndexer], because that version changes the result for
   * the same bytes. Always 20 bytes, the size of the store's keys.
   */
  fun keyFor(content: FileContentImpl, indexer: DataIndexer<*, *, FileContent>): ByteArray {
    val hash = IndexedHashesSupport.getOrInitIndexedHash(content)
    if (indexer !is CompositeDataIndexer<*, *, *, *>) return hash
    @Suppress("UNCHECKED_CAST")
    val composite = indexer as CompositeDataIndexer<*, *, Any, Any>
    val subIndexer = composite.calculateSubIndexer(content)
    val version = if (subIndexer == null) "" else composite.getSubIndexerVersion(subIndexer).toString()
    return MessageDigest.getInstance("SHA-1").run {
      update(hash)
      update(version.toByteArray(Charsets.UTF_8))
      digest()
    }
  }
}
