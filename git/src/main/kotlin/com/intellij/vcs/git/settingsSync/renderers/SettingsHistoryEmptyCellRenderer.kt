package com.intellij.vcs.git.settingsSync.renderers

import com.intellij.vcs.git.settingsSync.table.SettingsHistoryTable
import com.intellij.vcs.git.settingsSync.table.SettingsHistoryTableRow

internal class SettingsHistoryEmptyCellRenderer : SettingsHistoryCellRenderer() {
  override fun customizeHistoryCellRenderer(table: SettingsHistoryTable,
                                            row: SettingsHistoryTableRow,
                                            selected: Boolean,
                                            hasFocus: Boolean,
                                            rowIndex: Int) {
    // Nothing here. The cell is empty
  }
}