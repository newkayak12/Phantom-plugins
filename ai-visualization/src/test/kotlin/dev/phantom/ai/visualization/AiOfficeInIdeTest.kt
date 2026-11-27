// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.visualization.actions.OpenAiOfficeAction
import dev.phantom.ai.visualization.editor.AiOfficeEditor
import dev.phantom.ai.visualization.editor.AiOfficeEditorProvider
import dev.phantom.ai.visualization.editor.AiOfficeFile
import dev.phantom.ai.visualization.editor.OfficeLoader
import dev.phantom.ai.visualization.settings.VizConfigurable
import dev.phantom.ai.visualization.settings.VizSettings
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.phantom.ai.api.AiAgent
import dev.phantom.ai.api.AiChangeListener
import dev.phantom.ai.api.AiSession
import dev.phantom.ai.api.AiWorktree
import dev.phantom.ai.api.PhantomAiApi
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.testFramework.PlatformTestUtil
import java.awt.Container
import javax.swing.JComboBox

class AiOfficeInIdeTest : BasePlatformTestCase() {
  override fun tearDown() {
    try {
      FileEditorManager.getInstance(project).closeFile(AiOfficeFile.of(project))
    } finally {
      super.tearDown()
    }
  }

  fun testAiIntegrationPluginIsLoaded() {
    assertNotNull("dev.phantom.ai not in the sandbox", PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.ai")))
    assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.phantom.ai.visualization")))
  }

  fun testActionOpensExactlyOneAiOfficeEditor() {
    myFixture.testAction(OpenAiOfficeAction())
    myFixture.testAction(OpenAiOfficeAction())
    val manager = FileEditorManager.getInstance(project)
    assertEquals(1, manager.openFiles.count { it is AiOfficeFile })
    assertTrue(manager.isFileOpen(AiOfficeFile.of(project)))
    // the headless test editor manager shows a text editor in the tab, so the editor itself comes from the provider the platform picks
    val providers = com.intellij.openapi.fileEditor.ex.FileEditorProviderManager.getInstance().getProviderList(project, AiOfficeFile.of(project))
    assertTrue(providers.any { it is AiOfficeEditorProvider })
    val editor = providers.filterIsInstance<AiOfficeEditorProvider>().single().createEditor(project, AiOfficeFile.of(project))
    try {
      assertTrue(editor is AiOfficeEditor)
      assertEquals("AI Visualization", editor.name)
      assertSame(AiOfficeFile.of(project), editor.file)
    } finally {
      com.intellij.openapi.util.Disposer.dispose(editor)
    }
  }

  fun testProviderAcceptsOnlyItsFile() {
    val provider = AiOfficeEditorProvider()
    assertTrue(provider.accept(project, AiOfficeFile.of(project)))
    assertFalse(provider.accept(project, myFixture.configureByText("a.txt", "x").virtualFile))
  }

  fun testOneFilePerProject() {
    assertSame(AiOfficeFile.of(project), AiOfficeFile.of(project))
  }

  fun testSettingsOfferEveryThemeAndApplyingOneReRendersAnOpenOffice() {
    val settings = VizSettings.getInstance()
    val before = settings.themeId
    settings.themeId = "A"
    val editor = AiOfficeEditorProvider().createEditor(project, AiOfficeFile.of(project)) as AiOfficeEditor
    val configurable = VizConfigurable()
    try {
      assertEquals("A", editor.panel().canvas.theme.id)
      fun combos(c: java.awt.Component): List<JComboBox<*>> =
        (if (c is JComboBox<*>) listOf(c) else emptyList()) + ((c as? Container)?.components?.flatMap { combos(it) } ?: emptyList())
      val combo = combos(configurable.createComponent()).single()
      assertEquals(listOf("A", "B", "C", "D", "E", "F"), (0 until combo.itemCount).map { (combo.getItemAt(it) as dev.phantom.ai.visualization.theme.Theme).id })
      combo.selectedIndex = 1
      assertTrue(configurable.isModified)
      configurable.apply()
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals("B", settings.themeId)
      assertEquals("B", editor.panel().canvas.theme.id)
      assertEquals("B", editor.panel().canvas.getClientProperty("phantom.aiviz.theme"))
      combo.selectedIndex = 2
      configurable.apply()
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals("C", editor.panel().canvas.theme.id)
      assertEquals("C", editor.panel().canvas.getClientProperty("phantom.aiviz.theme"))
      settings.themeId = "A"
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals("A", editor.panel().canvas.theme.id)
    } finally {
      configurable.disposeUIResources()
      settings.themeId = before
      Disposer.dispose(editor)
    }
  }

  fun testHiddenTabIgnoresChangesAndListsWorktreesOncePerProject() {
    val loads = AtomicInteger()
    val worktreeReads = AtomicInteger()
    val projectSessionReads = AtomicInteger()
    class FakeApi : PhantomAiApi {
      var listener: AiChangeListener? = null
      override fun sessions(): List<AiSession> = emptyList<AiSession>().also { loads.incrementAndGet() }
      override fun sessions(project: Project): List<AiSession> = emptyList<AiSession>().also { projectSessionReads.incrementAndGet() }
      override fun worktrees(project: Project): List<AiWorktree> = emptyList<AiWorktree>().also { worktreeReads.incrementAndGet() }
      override fun addChangeListener(parent: Disposable, listener: AiChangeListener) {
        this.listener = listener
      }
      override fun focusSession(project: Project, tabId: String) = false
      override fun startSession(project: Project, worktreePath: String, agent: AiAgent): String? = null
    }
    val fake = FakeApi()
    val editor = AiOfficeEditor(project, AiOfficeFile.of(project), OfficeLoader({ fake }, testRootDisposable))
    try {
      val deadline = System.currentTimeMillis() + 10_000
      while (loads.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      }
      assertEquals("the editor reads once when it is created", 1, loads.get())
      val worktreesPerLoad = worktreeReads.get()
      val open = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.count { !it.isDefault && !it.isDisposed }
      assertEquals("one worktree listing per open project", open, worktreesPerLoad)
      val listener = fake.listener
      assertNotNull(listener)
      repeat(5) { listener!!.changed() }
      Thread.sleep(AiOfficeEditor.DEBOUNCE_MS * 4L)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals("a tab that is not showing ignores change events", 1, loads.get())
      assertEquals(worktreesPerLoad, worktreeReads.get())
      assertEquals("sessions are filtered from the IDE-wide list, not listed per project through git again", 0, projectSessionReads.get())
    } finally {
      Disposer.dispose(editor)
    }
  }
}