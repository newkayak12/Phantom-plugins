// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import java.nio.file.Path

/** Two checkouts of the same repository are two directories with identical file names and contents. */
object CheckoutFixture {
  const val MAIN = "src/p/Main.java"

  /** Writes 20 Java files below [root] and returns their paths relative to it. */
  fun writeJavaCheckout(root: Path): List<String> {
    val files = linkedMapOf(
      "src/p/Greeter.java" to "package p;\npublic class Greeter {\n  public String greet(String name) { return name; }\n}\n",
      MAIN to """
        package p;
        public class Main {
          void run(Greeter g) {
            Runnable r = () -> g.greet("x");
            g.greet(null);
            String s = "a" + g.greet("b");
          }
        }
      """.trimIndent() + "\n",
    )
    for (i in 0..17) files["src/p/gen/C$i.java"] = "package p.gen;\npublic class C$i {\n  int field$i;\n  void m$i() {}\n}\n"
    for ((path, text) in files) {
      val file = root.resolve(path)
      Files.createDirectories(file.parent)
      Files.writeString(file, text)
    }
    return files.keys.toList()
  }

  fun openCheckout(root: Path, parent: Disposable, level: LanguageLevel? = null): Project {
    val project = PlatformTestUtil.loadAndOpenProject(root, parent)
    val rootFile = virtualFile(root)
    val module = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "m", rootFile)
    if (level != null) IdeaTestUtil.setModuleLanguageLevel(module, level, parent)
    PsiTestUtil.addSourceRoot(module, virtualFile(root.resolve("src")))
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    return project
  }

  fun virtualFile(path: Path): VirtualFile = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!
}
