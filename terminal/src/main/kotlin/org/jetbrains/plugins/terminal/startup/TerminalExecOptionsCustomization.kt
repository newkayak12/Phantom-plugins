// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.terminal.startup

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import org.jetbrains.plugins.terminal.ShellStartupOptions

/**
 * Applies the terminal customizers to [options] and returns the updated options.
 *
 * The customizers ([org.jetbrains.plugins.terminal.LocalTerminalCustomizer] and [ShellExecOptionsCustomizer])
 * always run on the backend via [TerminalExecOptionsCustomizationRemoteApi], because in the RemDev scenario they
 * are expected to be present on the backend and access backend project model.
 */
internal fun applyExecOptionsCustomizers(project: Project, options: ShellStartupOptions): ShellStartupOptions {
  if (serviceOrNull<TerminalExecOptionsCustomizationRemoteApi>() == null) { // Phantom: an application service of the backend module, not RPC
    logger<TerminalExecOptionsCustomizationRemoteApi>().info("Skipping backend-side options customization. The service is not available.")
    return options
  }

  val shellCommand = requireNotNull(options.shellCommand) { "Shell command must not be null, $options" }
  // We need to pass the native path of the working directory in the remote environment.
  // So we use EelPath.toString() here.
  val workingDirectory = options.workingDirectoryEelPathNotNull.toString()

  val request = TerminalExecOptionsCustomizationRequest(
    projectId = project.projectId(),
    shellCommand = shellCommand,
    workingDirectory = workingDirectory,
    envVariables = options.envVariables,
    shellIntegrationAvailable = options.shellIntegration != null,
    eelDescriptor = options.eelDescriptorNotNull,
  )
  val response: TerminalExecOptionsCustomizationResponse = runBlockingMaybeCancellable {
    TerminalExecOptionsCustomizationRemoteApi.getInstance().customizeExecOptions(request)
  }

  return options.builder()
    .shellCommand(response.shellCommand)
    .envVariables(response.envVariables)
    .build()
}
