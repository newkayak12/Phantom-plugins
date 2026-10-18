// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.EditorNotifications
import dev.phantom.http.core.HttpEnvironmentService
import dev.phantom.http.core.HttpImports
import dev.phantom.http.core.HttpRequestBlock
import dev.phantom.http.core.HttpVariables
import dev.phantom.http.core.PhantomHttpBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.awt.Component
import java.net.http.HttpTimeoutException

/** Runs requests from a `.http` file: environment choice on the EDT, PSI reading in a read action, sending on IO. */
object HttpRunner {
  /** Runs [blocks] one after another. Asks for an environment first when the file has several and none is chosen. */
  fun run(project: Project, file: VirtualFile, blocks: List<HttpRequestBlock>, popupOwner: Component?) {
    runSteps(project, file, blocks.map { HttpImports.Step(it, emptyMap()) }, popupOwner)
  }

  /**
   * Runs [steps] one after another; a step may be a request of an imported file, with the values its `run` line gives.
   * The environment is the one chosen for [file], the file the run started from.
   */
  fun runSteps(project: Project, file: VirtualFile, steps: List<HttpImports.Step>, popupOwner: Component?) {
    if (steps.isEmpty()) return
    val pointers = steps.map { RunStep(SmartPointerManager.createPointer(it.block), it.overrides) }
    val envService = HttpEnvironmentService.getInstance(project)
    val names = envService.environmentsFor(file).names
    val selected = envService.selectedEnvironment(file)
    when {
      selected != null || names.isEmpty() -> start(project, pointers, selected)
      names.size == 1 -> {
        envService.selectEnvironment(file, names[0])
        EditorNotifications.getInstance(project).updateNotifications(file)
        start(project, pointers, names[0])
      }
      else -> chooseEnvironment(project, file, popupOwner) { start(project, pointers, it) }
    }
  }

  fun chooseEnvironment(project: Project, file: VirtualFile, owner: Component?, onChosen: (String?) -> Unit) {
    val service = HttpEnvironmentService.getInstance(project)
    val none = PhantomHttpBundle.message("http.environment.none")
    val items = listOf(none) + service.environmentsFor(file).names
    val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
      .setTitle(PhantomHttpBundle.message("http.environment.choose"))
      .setItemChosenCallback { item ->
        val env = item.takeIf { it != none }
        service.selectEnvironment(file, env)
        EditorNotifications.getInstance(project).updateNotifications(file)
        onChosen(env)
      }
      .createPopup()
    if (owner != null && owner.isShowing) popup.showInCenterOf(owner) else popup.showCenteredInCurrentWindow(project)
  }

  private class RunStep(val pointer: SmartPsiElementPointer<HttpRequestBlock>, val overrides: Map<String, String>)

  private fun start(project: Project, steps: List<RunStep>, environment: String?) {
    val service = HttpExecutionService.getInstance(project)
    service.launch {
      for (step in steps) {
        val title = readAction { step.pointer.element?.presentableName } ?: continue
        val panel = withContext(Dispatchers.EDT) { HttpResponseToolWindow.newTab(project, title) }
        withContext(Dispatchers.EDT) { panel.job = coroutineContext[Job] }
        if (!runOne(service, step, environment, panel)) break
      }
    }
  }

  /** False when the run was cancelled, so "Run All" stops too. */
  private suspend fun runOne(
    service: HttpExecutionService,
    step: RunStep,
    environment: String?,
    panel: HttpResponsePanel,
  ): Boolean {
    var resolver: HttpVariables.Resolver? = null
    val request = try {
      readAction {
        val block = step.pointer.element ?: throw HttpRequestBuildException(PhantomHttpBundle.message("http.run.no.request"))
        val r = HttpVariables.resolver(block.containingFile, service.globals, environment, step.overrides)
        resolver = r
        HttpRequestBuilder.build(block, r)
      }
    }
    catch (e: HttpRequestBuildException) {
      withContext(Dispatchers.EDT) { panel.showError(null, e.message ?: "") }
      return true
    }
    withContext(Dispatchers.EDT) { panel.showSending(request.requestLine) }
    try {
      val response = service.send(request)
      val body = HttpResponseFormatter.prepare(response)
      // the script interpreter loads only when a request has a handler
      val report = if (request.hasHandlers) {
        val lookup = resolver
        HttpHandlerRunner.run(request.handlers, request, response, service.globals) { lookup?.lookup(it) }
      }
      else null
      withContext(Dispatchers.EDT) { panel.showResponse(request, response, body, report) }
      return true
    }
    catch (e: CancellationException) {
      withContext(Dispatchers.EDT + kotlinx.coroutines.NonCancellable) {
        panel.showError(request.requestLine, PhantomHttpBundle.message("http.run.cancelled"))
      }
      throw e
    }
    catch (e: HttpTimeoutException) {
      withContext(Dispatchers.EDT) { panel.showError(request.requestLine, PhantomHttpBundle.message("http.run.timeout")) }
    }
    catch (e: Exception) {
      val cause = generateSequence<Throwable>(e) { it.cause }.last()
      val message = listOfNotNull(cause.javaClass.simpleName, cause.message).joinToString(": ")
      withContext(Dispatchers.EDT) { panel.showError(request.requestLine, PhantomHttpBundle.message("http.run.failed", message)) }
    }
    return true
  }
}
