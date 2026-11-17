// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import com.intellij.execution.Location
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.phantom.http.core.*
import java.util.function.Function
import javax.swing.JComponent

/** ▶ on the first token of each request line and of each `run` line. */
class HttpRunLineMarkerContributor : RunLineMarkerContributor() {
  override fun getInfo(element: PsiElement): Info? {
    if (element.firstChild != null) return null
    val line = element.parent?.let { if (it is HttpRequestLine || it is HttpRun) it else it.parent as? HttpRequestLine } ?: return null
    if (PsiTreeUtil.getDeepestFirst(line) != element) return null
    val manager = ActionManager.getInstance()
    val action = manager.getAction(RUN_ACTION_ID) ?: return null
    // the same menu also runs the whole file
    val actions = listOfNotNull(action, manager.getAction(RUN_ALL_ACTION_ID)).toTypedArray()
    return Info(AllIcons.Actions.Execute, actions) { PhantomHttpBundle.message("http.action.run.text") }
  }

  override fun isDumbAware(): Boolean = true

  companion object {
    const val RUN_ACTION_ID: String = "PhantomHttp.RunRequest"
    const val RUN_ALL_ACTION_ID: String = "PhantomHttp.RunAll"
  }
}

private fun httpFile(e: AnActionEvent): HttpRequestFile? = e.getData(CommonDataKeys.PSI_FILE) as? HttpRequestFile

/** Runs the request (or the `run` line) at the gutter icon or at the caret. */
class HttpRunRequestAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = target(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val target = target(e) ?: return
    val vFile = target.containingFile.virtualFile ?: return
    val steps = when (target) {
      is HttpRun -> HttpImports.steps(target)
      is HttpRequestBlock -> listOf(HttpImports.Step(target, emptyMap()))
      else -> emptyList()
    }
    HttpRunner.runSteps(project, vFile, steps, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
  }

  /** The `run` line at the caret, else the block with a request line. */
  private fun target(e: AnActionEvent): PsiElement? {
    val element = e.getData(Location.DATA_KEY)?.psiElement
                  ?: httpFile(e)?.let { file -> e.getData(CommonDataKeys.CARET)?.offset?.let { file.findElementAt(it) ?: file.findElementAt(it - 1) } }
                  ?: return null
    if (element.containingFile !is HttpRequestFile) return null
    PsiTreeUtil.getParentOfType(element, HttpRun::class.java, false)?.let { return it }
    val block = PsiTreeUtil.getParentOfType(element, HttpRequestBlock::class.java, false) ?: return null
    return block.takeIf { it.requestLine != null } ?: block.runs.singleOrNull()
  }
}

class HttpRunAllRequestsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = httpFile(e)
    e.presentation.isEnabledAndVisible = file != null && (file.requests.isNotEmpty() || file.blocks.any { it.runs.isNotEmpty() })
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = httpFile(e) ?: return
    // `run` lines take part in their place
    HttpRunner.runSteps(project, file.virtualFile ?: return, HttpImports.steps(file), e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
  }
}

class HttpSelectEnvironmentAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = httpFile(e)?.virtualFile
    val project = e.project
    e.presentation.isEnabledAndVisible = file != null && project != null &&
                                         !HttpEnvironmentService.getInstance(project).environmentsFor(file).isEmpty
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = httpFile(e)?.virtualFile ?: return
    HttpRunner.chooseEnvironment(project, file, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) {}
  }
}

/** In a `.http` editor, the run shortcut wins over other actions bound to the same keys. */
class HttpActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (context.getData(CommonDataKeys.PSI_FILE) !is HttpRequestFile) return emptyList()
    return actions.filterIsInstance<HttpRunRequestAction>()
  }
}

/** One line above a `.http` editor when env files exist: the environment in use, with links to change it and run all. */
class HttpEnvironmentNotificationProvider : EditorNotificationProvider {
  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
    if (file.fileType != HttpRequestFileType.INSTANCE) return null
    val service = HttpEnvironmentService.getInstance(project)
    if (service.environmentsFor(file).isEmpty) return null
    val selected = service.selectedEnvironment(file)
    return Function { editor ->
      EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
        text = PhantomHttpBundle.message("http.environment.current", selected ?: PhantomHttpBundle.message("http.environment.none"))
        createActionLabel(PhantomHttpBundle.message("http.environment.change")) {
          HttpRunner.chooseEnvironment(project, file, this) {}
        }
        createActionLabel(PhantomHttpBundle.message("http.action.run.all.text")) {
          val psi = PsiManager.getInstance(project).findFile(file) as? HttpRequestFile ?: return@createActionLabel
          HttpRunner.runSteps(project, file, HttpImports.steps(psi), this)
        }
      }
    }
  }
}
