// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.visualization

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.visualization.actions.OpenAiOfficeAction
import dev.phantom.ai.visualization.editor.AiOfficeEditor
import dev.phantom.ai.visualization.editor.AiOfficeEditorProvider
import dev.phantom.ai.visualization.editor.AiOfficeFile

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
      assertEquals("AI Office", editor.name)
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
}
