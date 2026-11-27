// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.rules.TempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.HexFormat

class GitBlobsTest {
  @Rule @JvmField val appRule = ApplicationRule()

  @Rule @JvmField val tempDir = TempDirectory()

  @Before
  fun setUp() = GitBlobs.clear()

  @Test
  fun `test a clean file gives the blob id git stored`() {
    val root = tempDir.newDirectory("repo").toPath()
    val file = committed(root, "src/A.java", "class A {}\n")
    assertArrayEquals(hashObject(root, "src/A.java"), GitBlobs.blobOf(vFile(file)))
  }

  @Test
  fun `test a changed file gives none`() {
    val root = tempDir.newDirectory("repo").toPath()
    val file = committed(root, "A.java", "class A {}\n")
    Files.writeString(file, "class A { int changed; }\n")
    GitBlobs.clear()
    assertNull(GitBlobs.blobOf(vFile(file)))
  }

  @Test
  fun `test a linked worktree reads its own index`() {
    val root = tempDir.newDirectory("repo").toPath()
    committed(root, "A.java", "class A {}\n")
    val linked = root.resolveSibling("linked")
    git(root, "worktree", "add", "-q", linked.toString())
    val file = linked.resolve("A.java")
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000))
    // The checkout stat'ed its files when it wrote them; back-dated ones are refreshed in the index.
    git(linked, "update-index", "--refresh", "-q")
    assertArrayEquals(hashObject(root, "A.java"), GitBlobs.blobOf(vFile(file)))
  }

  @Test
  fun `test the parsed indexes kept are bounded and dropped when a project closes`() {
    repeat(GitBlobs.MAX_INDEXES + 3) {
      val root = tempDir.newDirectory("repo$it").toPath()
      GitBlobs.blobOf(vFile(committed(root, "A.java", "class A {}\n")))
    }
    assertEquals(GitBlobs.MAX_INDEXES, GitBlobs.cachedIndexes)
    GitBlobsReleaser().projectClosed(ProjectManager.getInstance().defaultProject)
    assertEquals(0, GitBlobs.cachedIndexes)
    assertEquals(0, GitBlobs.cachedDirs)
  }

  private fun vFile(path: Path) = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!

  companion object {
    /** Commits [text] at [relative] in a new repository at [root], with the file older than the index (not racily clean). */
    fun committed(root: Path, relative: String, text: String): Path {
      if (!Files.isDirectory(root.resolve(".git"))) git(root, "init", "-q")
      val file = root.resolve(relative)
      Files.createDirectories(file.parent)
      Files.writeString(file, text)
      Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 10_000))
      git(root, "add", relative)
      git(root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c")
      return file
    }

    fun hashObject(root: Path, relative: String): ByteArray = HexFormat.of().parseHex(git(root, "hash-object", relative).trim())

    fun git(dir: Path, vararg args: String): String {
      val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
      val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
      check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
      return out
    }
  }
}
