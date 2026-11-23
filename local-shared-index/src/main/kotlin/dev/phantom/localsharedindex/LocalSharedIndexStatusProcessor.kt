// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IndexedFile
import java.io.IOException

/**
 * Called by the platform's scanning (UnindexedFilesFinder) before it loads a file for an index: when another file with
 * the same content key is indexed (the donor), records the file as its alias and reports it indexed, so the platform
 * neither loads nor indexes nor writes it. Queries reach the donor's data through [CachingIndex].
 */
internal class LocalSharedIndexStatusProcessor(private val project: Project) : FileIndexingStatusProcessor {
  override fun shouldProcessUpToDateFiles(): Boolean = false

  override fun processUpToDateFile(file: IndexedFile, inputId: Int, indexId: ID<*, *>): Boolean = true

  override fun tryIndexFileWithoutContent(file: IndexedFile, inputId: Int, indexId: ID<*, *>): Boolean {
    if (!LocalSharedIndexAllowlist.aliases(indexId.name)) return false
    val aliases = AliasRegistry.of(indexId.name)
    if (!aliases.hasDonors) return false
    val vFile = file.file
    if (vFile !is VirtualFileWithId) return false
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.name == indexId } ?: return false
    // A file clean in its git work tree is matched by blob id, which costs no read of the file: reading and hashing every
    // file of a fresh checkout made scanning slower than the indexing it saved. Such a file without a blob donor is not
    // read either; only files git cannot vouch for fall back to the content key.
    val blobKey = if (aliases.hasBlobDonors) blobHashOf(file)?.let { CacheKeys.blobKeyFor(it, file, extension.indexer) } else null
    val donor = if (blobKey != null) {
      aliases.donorForBlob(blobKey) ?: return false
    }
    else {
      // VFS attributes only: a file no donor could match is not read.
      if (!aliases.mayHaveDonor(vFile.length, vFile.name)) return false
      aliases.donorFor(keyOf(vFile, extension) ?: return false) ?: return false
    }
    if (donor == inputId) return false
    aliases.alias(inputId, donor)
    return true
  }

  override fun hasIndexForFile(file: VirtualFile, inputId: Int, extension: FileBasedIndexExtension<*, *>): Boolean =
    LocalSharedIndexAllowlist.aliases(extension.name.name) && AliasRegistry.of(extension.name.name).donorOf(inputId) != null

  // Scanning asks every index of one file in a row on one thread: the blob id is looked up and the indexed hash over it
  // computed once per file, not once per index.
  private val lastBlobHash = ThreadLocal<Pair<VirtualFile, ByteArray?>?>()

  private fun blobHashOf(file: IndexedFile): ByteArray? {
    lastBlobHash.get()?.takeIf { it.first == file.file }?.let { return it.second }
    val hash = GitBlobs.blobOf(file.file)?.let { CacheKeys.blobHash(file, it) }
    lastBlobHash.set(file.file to hash)
    return hash
  }

  private fun keyOf(file: VirtualFile, extension: FileBasedIndexExtension<*, *>): ByteArray? = try {
    CacheKeys.keyFor(contentOf(file), extension.indexer)
  }
  catch (e: IOException) {
    LOG.debug("Cannot read ${file.path} for its content key", e)
    null
  }

  // Scanning asks every index of one file in a row on one thread: the content is read once, and its indexed hash, which
  // FileContentImpl keeps, is computed once.
  private val lastContent = ThreadLocal<FileContentImpl?>()

  private fun contentOf(file: VirtualFile): FileContentImpl {
    lastContent.get()?.takeIf { it.file == file }?.let { return it }
    return (FileContentImpl.createByFile(file, project) as FileContentImpl).also { lastContent.set(it) }
  }

  companion object {
    private val LOG = logger<LocalSharedIndexStatusProcessor>()
  }
}
