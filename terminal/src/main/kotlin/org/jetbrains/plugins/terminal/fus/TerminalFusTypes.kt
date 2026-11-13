// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.fus

import org.jetbrains.annotations.ApiStatus

// Phantom-plugins: the types the terminal code still uses from the usage statistics collectors, which are gone

@ApiStatus.Internal
enum class TerminalInsertedContentType {
  TEXT,
  FILE,
  DIRECTORY,
  CLIPBOARD_IMAGE,
  MULTIPLE_ITEMS,
}

internal enum class TimeSpanType(val description: String) {
  FROM_STARTUP_TO_SHOWN_CURSOR("time from startup to terminal cursor shown in initialization block"),
  FROM_STARTUP_TO_READY_PROMPT("time from startup to prompt ready for command input"),
  FROM_COMMAND_SUBMIT_TO_VISUALLY_STARTED("time from command submitted by user to command visually started"),
  FROM_COMMAND_SUBMIT_TO_ACTUALLY_STARTED("time from command submitted by user to command actually started in shell"),
  FROM_TEXT_IN_BUFFER_TO_TEXT_VISIBLE("time from command output text is read into text buffer to output is visible"),
}
