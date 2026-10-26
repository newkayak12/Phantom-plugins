// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.openapi.fileEditor.impl.EditorAutoClosingHandler
import com.intellij.openapi.fileEditor.impl.EditorComposite

/** Tabs in a group, collapsed or not, are not closed to stay under the tab limit: collapsing must never close a file. */
internal class TabGroupAutoClosingHandler : EditorAutoClosingHandler {
  override fun isClosingAllowed(composite: EditorComposite): Boolean =
    !TabGroupsService.isGroupedAnywhere(composite.file)
}
