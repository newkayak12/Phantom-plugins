// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.ide.scratch.RootType
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.PhantomDbBundle
import dev.phantom.db.core.PhantomDbIcons
import javax.swing.Icon

/**
 * "Database Consoles" in Scratches and Consoles: one folder per data source, named by its id and shown by its name.
 */
class DbConsoleRootType : RootType("consoles/phantom-db", PhantomDbBundle.message("db.consoles.root")) {
  override fun substituteName(project: Project, file: VirtualFile): String? =
    dataSourceOf(project, file)?.name ?: super.substituteName(project, file)

  override fun substituteIcon(project: Project, file: VirtualFile): Icon? =
    dataSourceOf(project, file)?.let { PhantomDbIcons.forDataSource(it) } ?: super.substituteIcon(project, file)

  private fun dataSourceOf(project: Project, file: VirtualFile) =
    if (file.isDirectory && file.parent?.path == FileUtil.toSystemIndependentName(ScratchFileService.getInstance().getRootPath(this)))
      DbDataSourceService.getInstance(project).find(file.name)
    else null

  companion object {
    fun getInstance(): DbConsoleRootType = findByClass(DbConsoleRootType::class.java)
  }
}
