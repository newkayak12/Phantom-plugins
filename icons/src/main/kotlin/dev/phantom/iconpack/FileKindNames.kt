// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * The kind icon name of a file for the file icon patchers, kept on the file per modification stamp. FileIconPatcher runs
 * on the EDT for editor tabs and file lists, where PsiManager.findFile would parse the file; there the cached name is
 * returned and a missing one is computed in a background read action, after which the open editor tab is repainted.
 */
internal object FileKindNames {
  private class Entry(val stamp: Long, val name: String?)

  private val NAME = Key.create<Entry>("phantom.icons.fileKindName")
  private val PENDING = Key.create<Long>("phantom.icons.fileKindPending")

  fun name(file: VirtualFile, project: Project, compute: (PsiFile) -> String?): String? {
    val stamp = file.modificationStamp
    val cached = file.getUserData(NAME)
    if (cached != null && cached.stamp == stamp) return cached.name
    if (!ApplicationManager.getApplication().isDispatchThread) {
      val name = ReadAction.compute<String?, RuntimeException> { computeName(file, project, compute) }
      file.putUserData(NAME, Entry(stamp, name))
      return name
    }
    if (file.getUserData(PENDING) != stamp) {
      file.putUserData(PENDING, stamp)
      ReadAction.nonBlocking<String?> { computeName(file, project, compute) }
        .expireWith(project)
        .finishOnUiThread(ModalityState.any()) { name ->
          file.putUserData(NAME, Entry(stamp, name))
          file.putUserData(PENDING, null)
          if (file.isValid) FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }
    // An outdated name is closer to the truth than the plain file type icon until the new one arrives.
    return cached?.name
  }

  private fun computeName(file: VirtualFile, project: Project, compute: (PsiFile) -> String?): String? =
    if (project.isDisposed || !file.isValid) null else PsiManager.getInstance(project).findFile(file)?.let(compute)
}
