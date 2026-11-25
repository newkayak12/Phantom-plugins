// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces.home

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.WelcomeScreenProjectProvider
import com.intellij.platform.ide.nonModalWelcomeScreen.WelcomeScreenProjectActivity
import java.nio.file.Path

/**
 * Makes the Home project the welcome screen: with no project open, IntelliJ's non-modal welcome screen (this plugin's
 * copy of intellij.platform.ide.nonModalWelcomeScreen) opens it in a project frame instead of showing the modal
 * welcome frame. IntelliJ IDEA Community registers no WelcomeScreenProjectProvider, so without this one the copied
 * module stays inactive.
 *
 * Home lives in the IDE's config directory, not in the default project directory the user picked; it holds no files of
 * the user and is hidden from Recent Projects.
 */
internal class HomeProjectProvider : WelcomeScreenProjectProvider() {
  override fun getWelcomeScreenProjectName(): String = NAME

  override fun getWelcomeScreenProjectPath(): Path = path()

  override fun doIsWelcomeScreenProject(project: Project): Boolean = project.basePath?.let { Path.of(it) == path() } == true

  override fun doIsForceDisabledFileColors(): Boolean = true

  override fun doGetCreateNewFileProjectPrefix(): String = "untitled"

  // A file opened from the Finder or the command line opens the way it always has, not in Home.
  override fun canOpenFilesFromSystemFileManager(filePath: Path): Boolean = false

  // Every way Home opens comes through here (startup with nothing to reopen, the last project closed), so the welcome
  // screen's setup runs here instead of in a postStartupActivity that every project would pay for.
  override suspend fun doCreateOrOpenWelcomeScreenProject(path: Path, projectToClose: Project?): Project =
    super.doCreateOrOpenWelcomeScreenProject(path, projectToClose).also { WelcomeScreenProjectActivity().execute(it) }

  companion object {
    const val NAME: String = "Home"

    fun path(): Path = PathManager.getConfigDir().resolve("phantom-workspaces").resolve(NAME)
  }
}
