// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.phantom.mermaid.lang

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile

fun FileType.isMermaidType(): Boolean {
  return this == MermaidFileType
}

fun VirtualFile.isMermaidFile(): Boolean {
  return FileTypeRegistry.getInstance().isFileOfType(this, MermaidFileType)
}
