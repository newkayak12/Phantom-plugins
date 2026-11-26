// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.workspaces

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeRootPaneNorthExtension
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.workspaces.core.ProjectTabStrip
import dev.phantom.workspaces.core.ProjectTabs
import dev.phantom.workspaces.core.ProjectsList
import java.nio.file.Files
import java.nio.file.Path

class ProjectsListTest : BasePlatformTestCase() {
  private var strip: ProjectTabStrip? = null

  override fun tearDown() {
    try {
      strip?.detach()
    }
    catch (e: Throwable) {
      addSuppressedException(e)
    }
    finally {
      super.tearDown()
    }
  }

  private fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
  }

  fun testKnowsTheOtherCheckoutsOfARepositoryEvenIfNeverOpened() {
    val root = FileUtil.createTempDirectory("projects-list", null, true).toPath().toRealPath()
    val repo = root.resolve("shop")
    write(repo.resolve(".git/HEAD"), "ref: refs/heads/main\n")
    val gitDir = repo.resolve(".git/worktrees/login")
    val login = root.resolve("wt/login")
    write(gitDir.resolve("HEAD"), "ref: refs/heads/feature/login\n")
    write(gitDir.resolve("commondir"), "../..\n")
    write(gitDir.resolve("gitdir"), "${login.resolve(".git")}\n")
    write(login.resolve(".git"), "gitdir: $gitDir\n")
    val blog = Files.createDirectories(root.resolve("blog"))

    // the worktree was never opened: only the main checkout is open and blog is recent
    val known = ProjectsList.known(listOf(Triple("shop", repo.toString(), "shop")), listOf(blog.toString() to "blog"))
    assertEquals(listOf("shop", "blog", "login"), known.map { it.name })
    assertEquals(listOf("main", null, "feature/login"), known.map { it.branch })
    assertEquals("shop", known.first().key)
    assertTrue(known.first().isMain)
    assertEquals(known.first().repository, known.last().repository)
  }

  fun testARecentFolderGoneFromDiskIsNotListed() {
    val root = Files.createTempDirectory("projects-gone").toRealPath()
    val kept = Files.createDirectory(root.resolve("kept"))
    val gone = root.resolve("gone")
    val known = ProjectsList.known(emptyList(), listOf(kept.toString() to "kept", gone.toString() to "gone"))
    assertEquals(listOf("kept"), known.map { it.name })
  }

  fun testTheCurrentProjectIsListedOpenAndSelected() {
    strip = (IdeRootPaneNorthExtension.EP_NAME.extensionList.single { it.key == ProjectTabs.EXTENSION_KEY }
      .createComponent(project, isDocked = false) as ProjectTabStrip).also { it.attach() }
    val list = ProjectsList(project, testRootDisposable)
    PlatformTestUtil.waitWithEventsDispatching("the list loads", { list.shownRows.any { it.key === project } }, 10)
    val row = list.shownRows.single { it.key === project }
    assertTrue(row.isOpen && row.isCurrent)
    assertSame(row, list.list.selectedValue)

    list.searchField.text = "no project is called like this"
    assertTrue(list.shownRows.isEmpty())
    list.searchField.text = ""
    assertTrue(list.shownRows.any { it.key === project })
  }
}
