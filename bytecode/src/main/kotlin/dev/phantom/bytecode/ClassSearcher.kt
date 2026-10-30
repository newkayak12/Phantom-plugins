// Copyright 2000-2025 JetBrains s.r.o. and contributors, 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from the IntelliJ Community ByteCodeViewer plugin (plugins/ByteCodeViewer) into a Phantom plugin.
package dev.phantom.bytecode

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement

public interface ClassSearcher {
  public fun findClass(place: PsiElement): PsiClass?
}
