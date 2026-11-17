// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.tabs.core

import com.intellij.ui.JBColor
import java.awt.Color

/** Chrome's group colors: the saturated one for light themes, the pastel one for dark themes. */
private val TabGroupColor.rgb: Pair<Int, Int>
  get() = when (this) {
    TabGroupColor.GREY -> 0x5F6368 to 0xDADCE0
    TabGroupColor.BLUE -> 0x1A73E8 to 0x8AB4F8
    TabGroupColor.RED -> 0xD93025 to 0xF28B82
    TabGroupColor.YELLOW -> 0xE37400 to 0xFDD663
    TabGroupColor.GREEN -> 0x1E8E3E to 0x81C995
    TabGroupColor.PINK -> 0xD01884 to 0xFF8BCB
    TabGroupColor.PURPLE -> 0x9334E6 to 0xC58AF9
    TabGroupColor.CYAN -> 0x007B83 to 0x78D9EC
  }

/** The group's color in menus. */
val TabGroupColor.strong: Color
  get() = JBColor(rgb.first, rgb.second)

/** The tab background of a group member: the group color, faint. */
val TabGroupColor.soft: Color
  get() = JBColor(withAlpha(rgb.first, 0x2E), withAlpha(rgb.second, 0x3A))

private fun withAlpha(rgb: Int, alpha: Int): Color = Color((alpha shl 24) or rgb, true)

/** Text on [strong]: white on the saturated light-theme colors, near black on the pastel dark-theme ones. */
val TabGroupColor.onStrong: Color
  get() = JBColor(Color.WHITE, Color(0x202124))
