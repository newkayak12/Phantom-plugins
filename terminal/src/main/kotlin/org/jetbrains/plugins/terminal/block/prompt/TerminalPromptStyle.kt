// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.terminal.block.prompt

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
enum class TerminalPromptStyle {
  SINGLE_LINE, DOUBLE_LINE, SHELL
}