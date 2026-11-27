// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localinsights

import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * IntelliJ publishes InspectListener events only for batch runs (Code | Inspect Code, offline inspections); the
 * editor's on-the-fly highlighting keeps its per-tool latencies in internal classes, so those runs are not counted.
 */
class InspectionTimingListener : InspectListener {
  override fun inspectionFinished(
    duration: Long,
    threadId: Long,
    problemsCount: Int,
    tool: InspectionToolWrapper<*, *>,
    kind: InspectListener.InspectionKind,
    file: PsiFile?,
    project: Project,
  ) {
    InsightsStore.getInstance().recordInspection(tool.shortName, tool.displayName, duration)
  }
}
