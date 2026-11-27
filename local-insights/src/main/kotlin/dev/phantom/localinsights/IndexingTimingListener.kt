// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localinsights

import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.diagnostic.ProjectDumbIndexingHistory
import com.intellij.util.indexing.diagnostic.ProjectIndexingActivityHistoryListener

/** Adds each finished indexing session's per-index time to the plugin that registered the index. */
class IndexingTimingListener : ProjectIndexingActivityHistoryListener {
  override fun onFinishedDumbIndexing(history: ProjectDumbIndexingHistory) {
    val owners = indexOwners()
    val store = InsightsStore.getInstance()
    for ((indexId, stats) in history.totalStatsPerIndexer) {
      store.recordIndexing(ownerOf(indexId, owners), stats.totalNumberOfFiles.toLong(), stats.totalIndexValueChangerEvaluationTimeInAllThreads)
    }
  }

  private fun indexOwners(): Map<String, String> =
    FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.associate { extension ->
      extension.name.name to (PluginManager.getPluginByClass(extension.javaClass)?.takeIf { it.pluginId != PluginManagerCore.CORE_ID }?.name ?: "")
    }

  companion object {
    /** Indexes registered by the platform itself, or not found among the extensions (stubs, for one), count as the IDE's. */
    fun ownerOf(indexId: String, owners: Map<String, String>): String =
      owners[indexId]?.takeIf { it.isNotEmpty() } ?: LocalInsightsBundle.message("owner.platform")
  }
}
