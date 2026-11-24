// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.preview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.TextEditorWithPreview.Layout
import com.intellij.openapi.fileEditor.TextEditorWithPreview.MyFileEditorState
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.phantom.mermaid.markdown.preview.MermaidEditorWithPreview
import com.phantom.mermaid.markdown.preview.MermaidPreviewEditor

class MermaidEditorOnlyByDefaultTest : BasePlatformTestCase() {
  override fun tearDown() {
    try {
      PropertiesComponent.getInstance().unsetValue("TextEditorWithPreviewLayout")
    }
    finally {
      super.tearDown()
    }
  }

  override fun setUp() {
    super.setUp()
    // A split layout picked earlier in another tab, remembered app-wide by TextEditorWithPreview, must not apply.
    PropertiesComponent.getInstance().setValue("TextEditorWithPreviewLayout", Layout.SHOW_EDITOR_AND_PREVIEW.id)
  }

  fun testNewEditorShowsOnlyTheEditorAndCreatesNoDiagram() {
    val (editorWithPreview, preview) = createEditor()
    editorWithPreview.component
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertEquals(Layout.SHOW_EDITOR, editorWithPreview.getLayout())
    assertFalse("preview must stay hidden", preview.component.isVisible)
    assertEquals("no diagram (JCEF browser) may be created for a hidden preview", 0, preview.component.componentCount)
  }

  fun testRestoredSplitLayoutIsKept() {
    val (editorWithPreview, preview) = createEditor()
    editorWithPreview.setState(MyFileEditorState(Layout.SHOW_EDITOR_AND_PREVIEW, null, null, false))

    assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, editorWithPreview.getLayout())
    assertTrue("restored split layout must show the preview", preview.component.isVisible)
  }

  private fun createEditor(): Pair<MermaidEditorWithPreview, MermaidPreviewEditor> {
    myFixture.configureByText("diagram.mmd", "flowchart TD\n  A --> B\n")
    val textEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
    val preview = MermaidPreviewEditor(project, myFixture.file.virtualFile)
    val editorWithPreview = MermaidEditorWithPreview(textEditor, preview)
    Disposer.register(testRootDisposable, editorWithPreview)
    return editorWithPreview to preview
  }
}
