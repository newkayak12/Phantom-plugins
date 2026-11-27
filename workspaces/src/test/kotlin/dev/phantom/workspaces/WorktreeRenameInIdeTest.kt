// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.ide.RecentProjectMetaInfo
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.WorktreeRename
import dev.phantom.workspaces.core.WorktreeRenameListener
import java.nio.file.Files
import java.nio.file.Path

/** Rename… of a worktree in a real IDE: real git, the recent project follows, listeners hear it, an open project is refused. */
class WorktreeRenameInIdeTest : BasePlatformTestCase() {
  private fun git(dir: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
      .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())
  }

  fun testRenameMovesTheFolderTheRecentProjectAndTellsListeners() {
    val root = FileUtil.createTempDirectory("rename", null).toPath().toRealPath()
    val repo = Files.createDirectories(root.resolve("repo"))
    git(repo, "init", "-q", "-b", "main")
    Files.writeString(repo.resolve("a.txt"), "a")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "init")
    val worktree = root.resolve("feature")
    git(repo, "worktree", "add", "-q", "-b", "feature", worktree.toString())
    val manager = RecentProjectsManagerBase.getInstanceEx()
    val recent = FileUtil.toSystemIndependentName(worktree.toString())
    manager.addRecentPath(recent, RecentProjectMetaInfo().apply { displayName = "feature" })
    val heard = mutableListOf<Pair<String, String>>()
    ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
      .subscribe(WorktreeRename.TOPIC, WorktreeRenameListener { old, new -> heard += old to new })
    val previous = TestDialogManager.setTestInputDialog(TestInputDialog { "login" })
    var done: String? = null
    try {
      WorktreeRename.rename(project, worktree.toString(), { it.run() }) { done = it }
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
    finally {
      TestDialogManager.setTestInputDialog(previous)
      manager.removePath(recent)
    }
    val moved = root.resolve("login")
    assertTrue(Files.isRegularFile(moved.resolve("a.txt")))
    assertFalse(Files.exists(worktree))
    assertEquals(moved.toString(), done)
    assertEquals(listOf(worktree.toString() to moved.toString()), heard)
    assertTrue(manager.getRecentPaths().any { Path.of(it) == moved })
    assertFalse(manager.getRecentPaths().any { Path.of(it) == worktree })
    manager.removePath(FileUtil.toSystemIndependentName(moved.toString()))
  }

  fun testAWorktreeOpenAsAProjectIsRefusedWithAMessage() {
    val base = project.basePath!!
    assertSame(project, WorktreeRename.openProjectIn(base))
    var message: String? = null
    val previous = TestDialogManager.setTestDialog(TestDialog { message = it; 0 })
    val input = TestDialogManager.setTestInputDialog(TestInputDialog { fail("no name is asked for"); null })
    try {
      WorktreeRename.rename(project, base, { it.run() })
    }
    finally {
      TestDialogManager.setTestDialog(previous)
      TestDialogManager.setTestInputDialog(input)
    }
    assertTrue(message, message!!.contains("Close it first"))
  }
}
