// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager

/**
 * A Build in the IDE (JPS or delegated to Gradle or Maven) may have rewritten spring-configuration-metadata.json:
 * the next key request looks at the build output again. Nothing happens in projects that never asked for keys.
 */
class BootBuildListener(private val project: Project) : ProjectTaskListener {
  override fun finished(result: ProjectTaskManager.Result) {
    project.serviceIfCreated<ConfigMetadataService>()?.buildFinished()
  }
}
