// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.realide.probe

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlinx.coroutines.delay

/** The Project view as a user uses it: its tree shown, a file's node selected and double-clicked with posted events. */
internal object ProbeProjectView {
  /**
   * Shows the Project tool window, selects [file]'s node and posts a double click (press, release, click with
   * clickCount 1 then 2) on the node's row through the system event queue. Returns what was found and posted.
   */
  suspend fun doubleClick(ctx: ProbeContext, file: VirtualFile): Map<String, Any?> {
    val shown = ctx.edt {
      val tw = ToolWindowManager.getInstance(ctx.project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return@edt false
      tw.show(null)
      true
    }
    if (!shown) return mapOf("posted" to false, "reason" to "no Project tool window")
    ctx.waitUntil(5000) { ProjectView.getInstance(ctx.project).currentProjectViewPane?.tree?.isShowing == true }
    ctx.edt { ProjectView.getInstance(ctx.project).select(null, file, false) }
    var path: TreePath? = null
    ctx.waitUntil(8000) {
      val tree = ProjectView.getInstance(ctx.project).currentProjectViewPane?.tree ?: return@waitUntil false
      path = tree.selectionPath?.takeIf { p -> fileOf(p) == file }
      path != null && tree.getPathBounds(path) != null
    }
    val p = path ?: return mapOf("posted" to false, "reason" to "node of ${file.name} not selected in the Project view",
                                 "treeShowing" to ctx.edt { ProjectView.getInstance(ctx.project).currentProjectViewPane?.tree?.isShowing })
    return ctx.edt {
      val tree = ProjectView.getInstance(ctx.project).currentProjectViewPane.tree
      tree.scrollPathToVisible(p)
      val r = tree.getPathBounds(p)!!
      val x = r.x + minOf(r.width / 2, 20)
      val y = r.y + r.height / 2
      val q = Toolkit.getDefaultToolkit().systemEventQueue
      val down = InputEvent.BUTTON1_DOWN_MASK
      fun e(id: Int, mods: Int, clicks: Int) = MouseEvent(tree, id, System.currentTimeMillis(), mods, x, y, clicks, false, MouseEvent.BUTTON1)
      for (clicks in 1..2) {
        q.postEvent(e(MouseEvent.MOUSE_PRESSED, down, clicks))
        q.postEvent(e(MouseEvent.MOUSE_RELEASED, 0, clicks))
        q.postEvent(e(MouseEvent.MOUSE_CLICKED, 0, clicks))
      }
      mapOf("posted" to true, "via" to ProbeInput.VIA_QUEUE, "row" to tree.getRowForPath(p), "point" to listOf(x, y), "node" to p.lastPathComponent.toString())
    }.also { delay(300) }
  }

  private fun fileOf(p: TreePath): VirtualFile? {
    val user = (p.lastPathComponent as? DefaultMutableTreeNode)?.userObject
    return (user as? com.intellij.ide.util.treeView.AbstractTreeNode<*>)?.let { n ->
      (n.value as? com.intellij.psi.PsiFileSystemItem)?.virtualFile ?: (n.value as? VirtualFile)
    }
  }
}
