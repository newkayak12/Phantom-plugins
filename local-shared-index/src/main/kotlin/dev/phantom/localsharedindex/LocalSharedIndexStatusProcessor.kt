// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexEx
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtensionUpdatableIndex
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.indexing.StorageException
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
    // Archive entries are never aliases nor donors (CachingIndex.aliasOnUpdate).
    if (vFile !is VirtualFileWithId || !vFile.isInLocalFileSystem) return false
    if (indexId.name == LocalSharedIndexAllowlist.STUBS && !mayAliasStubs(file, vFile)) return false
    val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.name == indexId } ?: return false
    val donor = findDonor(aliases, file, vFile, extension) ?: return false
    if (donor == inputId || hasOwnData(inputId, indexId) || !aliases.isLive(donor)) return false
    aliases.alias(inputId, donor)
    return true
  }

  // A file clean in its git work tree is matched by blob id, which costs no read of the file: reading and hashing every
  // file of a fresh checkout made scanning slower than the indexing it saved. Such a file without a blob donor is not
  // read either; only files git cannot vouch for fall back to the content key.
  private fun findDonor(aliases: IndexAliases, file: IndexedFile, vFile: VirtualFile, extension: FileBasedIndexExtension<*, *>): Int? {
    val blobKey = if (aliases.hasBlobDonors) blobHashOf(file)?.let { CacheKeys.blobKeyFor(it, file, extension.indexer) } else null
    if (blobKey != null) return aliases.donorForBlob(blobKey)
    // VFS attributes only: a file no donor could match is not read.
    if (!aliases.mayHaveDonor(vFile.length, vFile.name)) return null
    return aliases.donorFor(keyOf(vFile, extension) ?: return null)
  }

  /**
   * Scanning marks a file it was told about here indexed without removing the data indexed for it before
   * (UnindexedFilesFinder.tryIndexWithoutContentViaInfrastructureExtension). A file changed while the IDE was closed would
   * keep its old keys besides the donor's, so such a file is indexed instead; indexing still makes it an alias without
   * mapping it (CachingIndex.aliasOnUpdate). Only files with a donor get here, and a fresh checkout's files have no data.
   */
  private fun hasOwnData(inputId: Int, indexId: ID<*, *>): Boolean {
    @Suppress("UNCHECKED_CAST")
    val index = (FileBasedIndex.getInstance() as FileBasedIndexEx).getIndex(indexId as ID<Any, Any>)
    val base = (index as? FileBasedIndexInfrastructureExtensionUpdatableIndex<*, *, *, *>)?.baseIndex ?: index
    return try {
      base.getIndexedFileData(inputId).isNotEmpty()
    }
    catch (e: StorageException) {
      LOG.debug("Cannot read the indexed data of $inputId in $indexId", e)
      true
    }
  }

  /**
   * Stubs only of the file types whose stubs depend on the file alone ([LocalSharedIndexAllowlist.cachesStubsFor]; a
   * `.kts` script never). StubTreeLoaderImpl.readFromVFile compares the length a Stubs update recorded for the file
   * (IndexingStampInfo) with its loaded text and reports a mismatch as an error. Aliasing here records nothing, so a file
   * whose recorded length is from other content is indexed instead.
   */
  private fun mayAliasStubs(file: IndexedFile, vFile: VirtualFile): Boolean {
    if (!LocalSharedIndexAllowlist.cachesStubsFor(file.fileType, file.fileName)) return false
    // A class file (binary) is never marked indexed without content: both the blob key here and the platform's own
    // PersistentSubIndexerRetriever.setIndexedState ask StubUpdatingIndex for the sub-indexer, and
    // StubTreeBuilder.getStubBuilderType casts the file to FileContent to pick the class file sub-builder from its bytes.
    if (file.fileType.isBinary) return false
    val recorded = StubTreeLoader.getInstance().getIndexingStampInfo(vFile) ?: return true
    return recorded.indexingByteLength == vFile.length
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
