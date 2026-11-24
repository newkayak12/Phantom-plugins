// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.markdown.preview

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview

// phantom-idea: a new tab shows only the editor, so opening a diagram starts no JCEF browser. The initial layout, not just
// the default, so the layout TextEditorWithPreview remembers app-wide is ignored; only the file's own restored state
// keeps a split or preview layout.
internal class MermaidEditorWithPreview(
  editor: TextEditor,
  preview: MermaidPreviewEditor
): TextEditorWithPreview(editor, preview, defaultLayout = TextEditorWithPreview.Layout.SHOW_EDITOR, layout = TextEditorWithPreview.Layout.SHOW_EDITOR)
