// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.iconpack

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.FilePathIconProvider
import javax.swing.Icon

/**
 * Change lists, commit and history views draw a path that has no file behind it (deleted, or not yet fetched) with
 * the bare file type icon; give it the same icon the file had in the project tree.
 */
internal class MaterialFilePathIconProvider : FilePathIconProvider, DumbAware {
  override fun getIcon(filePath: FilePath, isDirectory: Boolean, project: Project?): Icon? {
    // a file that exists already gets its icon from the file icon providers
    if (isDirectory || filePath.virtualFile != null) return null
    return MaterialIcons.forFileName(filePath.name)
  }
}
