// Copyright 2000-2025 JetBrains s.r.o. and contributors, 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from the IntelliJ Community ByteCodeViewer plugin (plugins/ByteCodeViewer) into a Phantom plugin.
package dev.phantom.bytecode

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass

public interface BytecodeViewerClassFileFinder {
  public fun findClass(element: PsiClass, containing: PsiClass?): VirtualFile?
}