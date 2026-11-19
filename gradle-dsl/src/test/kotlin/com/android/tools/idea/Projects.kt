// Phantom: a stand-in for the Android Studio class of the same name, which the upstream tests use but which is not part of
// IntelliJ Community or any published artifact Phantom builds against. Only what these tests call, behaving the same.
@file:JvmName("Projects")
package com.android.tools.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.io.File

fun getBaseDirPath(project: Project): File =
  if (project.isDefault) File("") else File(FileUtil.toCanonicalPath(project.basePath!!))
