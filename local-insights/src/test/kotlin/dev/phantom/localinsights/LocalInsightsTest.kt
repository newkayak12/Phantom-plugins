// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localinsights

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LocalInsightsTest : BasePlatformTestCase() {
  class SlowInspection : LocalInspectionTool() {
    override fun getShortName(): String = "PhantomSlowTest"

    override fun getDisplayName(): String = "Slow test inspection"

    override fun getGroupDisplayName(): String = "Phantom"

    override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.WARNING

    override fun isEnabledByDefault(): Boolean = true
  }

  override fun setUp() {
    super.setUp()
    InsightsStore.getInstance().clear()
  }

  override fun tearDown() {
    try {
      InsightsStore.getInstance().clear()
    } finally {
      super.tearDown()
    }
  }

  fun testInspectionTimingsAddUpAndSortBySlowestTotal() {
    val store = InsightsStore.getInstance()
    store.recordInspection("Fast", "Fast one", 5)
    store.recordInspection("Slow", "Slow one", 40)
    store.recordInspection("Slow", "Slow one", 10)
    store.recordInspection("Fast", "Fast one", -3)

    val timings = store.slowestInspections()
    assertEquals(listOf("Slow", "Fast"), timings.map { it.shortName })
    assertEquals(InspectionTiming("Slow", "Slow one", 2, 50, 40), timings[0])
    assertEquals(InspectionTiming("Fast", "Fast one", 2, 5, 5), timings[1])
  }

  fun testBatchInspectionEventReachesTheStore() {
    val wrapper = LocalInspectionToolWrapper(SlowInspection())
    project.messageBus.syncPublisher(GlobalInspectionContextEx.INSPECT_TOPIC)
      .inspectionFinished(120, Thread.currentThread().id, 0, wrapper, InspectListener.InspectionKind.LOCAL, null, project)

    assertEquals(listOf(InspectionTiming("PhantomSlowTest", "Slow test inspection", 1, 120, 120)), InsightsStore.getInstance().slowestInspections())
  }

  fun testIndexingTimeAddsUpPerPluginAndUnknownIndexesBelongToThePlatform() {
    val owners = mapOf("yaml.keys" to "Phantom YAML", "core.index" to "")
    assertEquals("Phantom YAML", IndexingTimingListener.ownerOf("yaml.keys", owners))
    assertEquals(LocalInsightsBundle.message("owner.platform"), IndexingTimingListener.ownerOf("core.index", owners))
    assertEquals(LocalInsightsBundle.message("owner.platform"), IndexingTimingListener.ownerOf("Stubs", owners))

    val store = InsightsStore.getInstance()
    store.recordIndexing("Phantom YAML", 3, 1_000)
    store.recordIndexing("IDE platform", 10, 9_000)
    store.recordIndexing("Phantom YAML", 2, 500)
    assertEquals(listOf(IndexingTiming("IDE platform", 10, 9_000), IndexingTiming("Phantom YAML", 5, 1_500)), store.indexingByPlugin())
  }

  fun testPanelShowsTheStoreAndClearEmptiesIt() {
    InsightsStore.getInstance().recordInspection("Slow", "Slow one", 7)
    InsightsStore.getInstance().recordIndexing("Phantom YAML", 1, 3_000_000)
    val panel = LocalInsightsPanel(project)
    assertEquals(1, panel.inspectionModel.rowCount)
    assertEquals(3L, panel.indexingModel.getValueAt(0, 2))

    InsightsStore.getInstance().clear()
    panel.refresh()
    assertEquals(0, panel.inspectionModel.rowCount)
    assertEquals(0, panel.indexingModel.rowCount)
  }

  fun testDisableTurnsTheInspectionOffInTheProjectProfile() {
    myFixture.enableInspections(SlowInspection())
    val key = HighlightDisplayKey.find("PhantomSlowTest")!!
    val profileManager = InspectionProjectProfileManager.getInstance(project)
    assertTrue(profileManager.currentProfile.isToolEnabled(key))

    disableInspection(project, "PhantomSlowTest")

    assertFalse(profileManager.currentProfile.isToolEnabled(key))
  }

  fun testDescriptorRegistersNoStartupWork() {
    val descriptor = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
    assertTrue(descriptor.contains("dev.phantom.local-insights"))
    for (eager in listOf("postStartupActivity", "backgroundPostStartupActivity", "preloadingActivity", "applicationService")) {
      assertFalse(eager, descriptor.contains(eager))
    }
  }
}
