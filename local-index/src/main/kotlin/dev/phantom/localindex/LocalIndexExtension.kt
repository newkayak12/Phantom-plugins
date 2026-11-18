// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor
import com.intellij.util.indexing.FileBasedIndexInfrastructureExtension.InitializationResult
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.UpdatableIndex

/** Skeleton: registered with the platform but leaves every index and the indexing status check untouched. */
class LocalIndexExtension : FileBasedIndexInfrastructureExtension {
  override fun createFileIndexingStatusProcessor(project: Project): FileIndexingStatusProcessor? = null

  override fun <K, V> combineIndex(
    indexExtension: FileBasedIndexExtension<K, V>,
    baseIndex: UpdatableIndex<K, V, FileContent, *>,
  ): UpdatableIndex<K, V, FileContent, *>? = null

  override fun onFileBasedIndexVersionChanged(indexId: ID<*, *>) = Unit

  override fun onStubIndexVersionChanged(indexId: StubIndexKey<*, *>) = Unit

  override fun initialize(indexLayoutId: String?): InitializationResult = InitializationResult.SUCCESSFULLY

  override fun attachData(project: Project) = Unit

  override fun getPersistentStateRoot(): String = ROOT

  override fun resetPersistentState() = Unit

  override fun resetPersistentState(indexId: ID<*, *>) = Unit

  override fun shutdown() = Unit

  // -1 so that this extension never changes the version of the indexes it does not wrap
  override fun getVersion(): Int = -1

  companion object {
    const val ROOT = "phantom-local-index"

    fun getInstance(): LocalIndexExtension = FileBasedIndexInfrastructureExtension.EP_NAME.findExtensionOrFail(LocalIndexExtension::class.java)
  }
}
