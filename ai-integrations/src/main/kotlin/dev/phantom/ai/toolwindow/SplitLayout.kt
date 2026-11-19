// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai.toolwindow

import com.intellij.openapi.wm.ToolWindowType

/** Which way the tree column and the terminal column sit: side by side, or the tree above the terminals. */
enum class SplitOrientation { HORIZONTAL, VERTICAL }

/** Pure layout rules of the tool window panel (mockups B and D). */
object SplitLayout {
  const val DEFAULT_H = 0.3f
  const val DEFAULT_V = 0.35f
  const val KEY_H = "PhantomAi.split.h"
  const val KEY_V = "PhantomAi.split.v"
  const val KEY_TREE_HIDDEN = "PhantomAi.tree.hidden"

  /** Docked (or sliding): always side by side (mockup B). Floating or windowed: side by side when wide enough, else stacked (D). */
  fun orientation(type: ToolWindowType, width: Int, height: Int): SplitOrientation = when {
    type != ToolWindowType.FLOATING && type != ToolWindowType.WINDOWED -> SplitOrientation.HORIZONTAL
    width >= maxOf(600.0, 0.8 * height) -> SplitOrientation.HORIZONTAL
    else -> SplitOrientation.VERTICAL
  }

  /**
   * Whether + Claude, + Codex, Board and Dashboard sit in one full-width row above the split: when floating or windowed, or
   * when the tree column is hidden. Docked (or sliding) with the tree shown, they sit in the tree column.
   */
  fun buttonsInHeader(type: ToolWindowType, treeHidden: Boolean): Boolean =
    treeHidden || type == ToolWindowType.FLOATING || type == ToolWindowType.WINDOWED
}

/** Whether the tree column is hidden, and the proportion it gets again when shown. */
class TreeFold(var hidden: Boolean = false, var proportion: Float = SplitLayout.DEFAULT_H) {
  /** Hides a shown tree and shows a hidden one; [proportion] stays what it last was while visible. */
  fun toggle() {
    hidden = !hidden
  }

  /** The share of the width (or height) the tree gets now: none while hidden. */
  fun effectiveProportion(): Float = if (hidden) 0f else proportion
}
