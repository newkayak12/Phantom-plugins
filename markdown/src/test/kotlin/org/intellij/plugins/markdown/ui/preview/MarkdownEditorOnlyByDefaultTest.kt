// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.ui.preview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreview.Layout
import com.intellij.openapi.fileEditor.TextEditorWithPreview.MyFileEditorState
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.MarkdownBundle
import org.intellij.plugins.markdown.settings.MarkdownSettings

class MarkdownEditorOnlyByDefaultTest : BasePlatformTestCase() {
  override fun tearDown() {
    try {
      PropertiesComponent.getInstance().unsetValue(MarkdownBundle.message("markdown.editor.name") + "Layout")
    }
    finally {
      super.tearDown()
    }
  }

  override fun setUp() {
    super.setUp()
    // A split layout picked earlier in another Markdown tab, remembered app-wide by TextEditorWithPreview, must not apply.
    PropertiesComponent.getInstance().setValue(MarkdownBundle.message("markdown.editor.name") + "Layout", Layout.SHOW_EDITOR_AND_PREVIEW.id)
  }

  fun testNewEditorShowsOnlyTheEditorAndCreatesNoHtmlPanel() {
    val (editorWithPreview, preview) = createEditor()
    editorWithPreview.component
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertEquals(Layout.SHOW_EDITOR, editorWithPreview.getLayout())
    assertFalse("preview must stay hidden", preview.component.isVisible)
    assertNull("no HTML panel (JCEF) may be created for a hidden preview", preview.getUserData(MarkdownPreviewFileEditor.PREVIEW_BROWSER))
    assertEquals(0, preview.component.componentCount)
  }

  fun testReadmeOpenedAsPreviewOnProjectOpenShowsOnlyTheEditor() {
    // IdeProjectFrameAllocator marks README.md preview-only through this internal key before opening it on a project's first open.
    TextEditorWithPreview.getParentSplitEditor(null) // creates the key
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    val defaultLayoutForFile = Key.findKeyByName("TextEditorWithPreview.DefaultLayout") as Key<Layout>
    val (editorWithPreview, preview) = createEditor { it.putUserData(defaultLayoutForFile, Layout.SHOW_PREVIEW) }
    editorWithPreview.component
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertEquals(Layout.SHOW_EDITOR, editorWithPreview.getLayout())
    assertNull(preview.getUserData(MarkdownPreviewFileEditor.PREVIEW_BROWSER))
  }

  fun testRestoredSplitLayoutIsKept() {
    val (editorWithPreview, preview) = createEditor()
    editorWithPreview.setState(MyFileEditorState(Layout.SHOW_EDITOR_AND_PREVIEW, null, null, false))
    editorWithPreview.component

    assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, editorWithPreview.getLayout())
    assertTrue("restored split layout must show the preview", preview.component.isVisible)
  }

  private fun createEditor(
    prepare: (com.intellij.openapi.vfs.VirtualFile) -> Unit = {},
  ): Pair<MarkdownEditorWithPreview, MarkdownPreviewFileEditor> {
    myFixture.configureByText("README.md", "# Heading\n\ntext\n")
    val file = myFixture.file.virtualFile
    prepare(file)
    val textEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
    val preview = MarkdownPreviewFileEditor(project, file, myFixture.editor.document)
    val editorWithPreview = MarkdownEditorWithPreview(textEditor, preview, project, MarkdownSettings.getInstance(project))
    Disposer.register(testRootDisposable, editorWithPreview)
    return editorWithPreview to preview
  }
}
