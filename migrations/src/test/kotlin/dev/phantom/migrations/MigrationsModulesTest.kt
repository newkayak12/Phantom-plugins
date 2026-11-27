// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Modules that hold migrations while another module runs them. A light project has one module, so this test is heavy. */
class MigrationsModulesTest : HeavyPlatformTestCase() {
  private lateinit var root: VirtualFile

  override fun setUp() {
    super.setUp()
    root = tempDir.createVirtualDir()
  }

  override fun tearDown() {
    try {
      MigrationsGate.openInTests = true
    }
    finally {
      super.tearDown()
    }
  }

  private fun dir(path: String): VirtualFile = VfsTestUtil.createDir(root, path)

  /** An empty jar file; the gate reads only the file name. */
  private fun jar(name: String): String {
    val file = File(tempDir.newPath().toFile().apply { mkdirs() }, name)
    ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("META-INF/")) }
    val local = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    return VfsUtil.getUrlForLibraryRoot(local.toNioPath())
  }

  private fun newModule(name: String): Module = PsiTestUtil.addModule(project, EmptyModuleType.getInstance(), name, dir(name))

  fun testGateFollowsModulesThatDependOnIt() {
    MigrationsGate.openInTests = false
    val persistence = newModule("persistence")
    val app = newModule("app")
    assertFalse(MigrationsGate.uses(persistence, "flyway-core"))
    ModuleRootModificationUtil.addDependency(app, persistence)
    ModuleRootModificationUtil.addModuleLibrary(app, jar("flyway-core-10.0.0.jar"))
    assertTrue(MigrationsGate.uses(app, "flyway-core"))
    assertTrue(MigrationsGate.uses(persistence, "flyway-core"))
    assertFalse(MigrationsGate.uses(persistence, "liquibase-core"))
    assertFalse(MigrationsGate.uses(newModule("other"), "flyway-core"))
  }

  fun testClasspathIncludeFromADependency() {
    val persistence = newModule("persistence")
    val app = newModule("app")
    ModuleRootModificationUtil.addDependency(app, persistence)
    PsiTestUtil.addSourceRoot(persistence, dir("persistence/resources"), JavaResourceRootType.RESOURCE)
    PsiTestUtil.addSourceRoot(app, dir("app/resources"), JavaResourceRootType.RESOURCE)
    val shared = VfsTestUtil.createFile(root, "persistence/resources/db/shared.xml", "<databaseChangeLog/>")
    val master = VfsTestUtil.createFile(root, "app/resources/db/changelog/master.xml", "<databaseChangeLog/>")
    assertEquals(shared, resolveInclude(app, master, "classpath:db/shared.xml", relative = false, directory = false))
    assertNull(resolveInclude(persistence, shared, "classpath:db/changelog/master.xml", relative = false, directory = false))
  }

  /** Config is read from resource roots when the module has them; Java source roots are then not walked. */
  fun testConfigRootsPreferResourceRoots() {
    val app = newModule("app")
    val sources = dir("app/java")
    val resources = dir("app/resources")
    PsiTestUtil.addSourceRoot(app, sources)
    assertTrue(sources in configRoots(app, false))
    PsiTestUtil.addSourceRoot(app, resources, JavaResourceRootType.RESOURCE)
    assertEquals(listOf(resources), configRoots(app, false))
  }
}
