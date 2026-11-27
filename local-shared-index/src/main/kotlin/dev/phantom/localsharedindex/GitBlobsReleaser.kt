// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/** Drops [GitBlobs]' cached work trees and parsed git indexes when a project closes, so they do not outlive it. */
internal class GitBlobsReleaser : ProjectCloseListener {
  override fun projectClosed(project: Project) {
    GitBlobs.clear()
  }
}
