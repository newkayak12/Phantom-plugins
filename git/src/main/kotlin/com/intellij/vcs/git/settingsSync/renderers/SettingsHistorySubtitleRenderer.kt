package com.intellij.vcs.git.settingsSync.renderers

import com.intellij.icons.AllIcons
import com.intellij.vcs.git.settingsSync.record.ChangeRecord
import com.intellij.vcs.git.settingsSync.table.SettingsHistoryTable
import com.intellij.vcs.git.settingsSync.table.SettingsHistoryTableRow
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI

internal class SettingsHistorySubtitleRenderer : SettingsHistoryCellRenderer() {
  private val windowsIcon = AllIcons.FileTypes.MicrosoftWindows
  private val linuxIcon = AllIcons.Linux.Linux

  @Suppress("HardCodedStringLiteral")
  override fun customizeHistoryCellRenderer(table: SettingsHistoryTable,
                                            row: SettingsHistoryTableRow,
                                            selected: Boolean,
                                            hasFocus: Boolean,
                                            rowIndex: Int) {
    val record = row.record
    val textAttributes = if (isGreyedOut(table, rowIndex)) SimpleTextAttributes.GRAY_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES

    when (record.os) {
      ChangeRecord.OperatingSystem.WINDOWS -> icon = windowsIcon
      ChangeRecord.OperatingSystem.LINUX -> icon = linuxIcon
      ChangeRecord.OperatingSystem.MAC -> appendWithClipping("macOS  ", textAttributes, DefaultFragmentTextClipper.INSTANCE)
      null -> {}
    }
    if (icon == null) {
      ipad = JBUI.insetsLeft(20)
    }
    else {
      ipad = JBUI.insetsLeft(22)
    }

    if (isGreyedOut(table, rowIndex)) {
      iconOpacity = 0.60f
    }
    iconTextGap = 1

    appendWithClipping("${record.host}, ${record.build}", textAttributes, DefaultFragmentTextClipper.INSTANCE)
  }
}