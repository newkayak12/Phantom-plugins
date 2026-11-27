// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.intention

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.phantom.mermaid.lang.psi.MermaidFile

/**
 * ThemeInspection reports light Mermaid themes only while the editor color scheme is dark, but the daemon does not
 * re-run inspections when the scheme changes, so its warnings stayed as they were for the previous scheme.
 * Restarts highlighting of the open Mermaid files only.
 */
internal class MermaidColorSchemeListener(private val project: Project) : EditorColorsListener {
  override fun globalSchemeChange(scheme: EditorColorsScheme?) {
    if (project.isDisposed) return
    val psiManager = PsiManager.getInstance(project)
    val analyzer = DaemonCodeAnalyzer.getInstance(project)
    for (file in FileEditorManager.getInstance(project).openFiles) {
      val psiFile = psiManager.findFile(file) as? MermaidFile ?: continue
      analyzer.restart(psiFile)
    }
  }
}
