// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.core.ConfigKeys
import dev.phantom.springboot.core.ConfigMetadataService
import dev.phantom.springboot.core.ConfigProperty
import dev.phantom.springboot.core.Deprecation
import dev.phantom.springboot.core.HintValue
import dev.phantom.springboot.core.ParsedMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Configuration keys kept in layers (libraries, build output and additional metadata, source): the same result as one flat
 * merge, a Java edit does not merge the library keys again, and new metadata files show up at once.
 */
class ConfigMetadataLayersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private var buildDir: Path? = null

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.boot; public class SpringApplication {}")
    myFixture.addClass("""
      package org.springframework.boot.context.properties;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface ConfigurationProperties { String value() default ""; String prefix() default ""; }
    """.trimIndent())
    val metadata = myFixture.tempDirFixture.createFile("bootlib/META-INF/spring-configuration-metadata.json", """
      {"properties": [
        {"name": "server.port", "type": "java.lang.Integer", "description": "Server HTTP port.", "defaultValue": 8080},
        {"name": "app.mail.host", "type": "java.lang.String", "description": "From the library.", "defaultValue": "lib"}
      ],
       "hints": [{"name": "app.mail.host", "values": [{"value": "smtp.lib"}]}],
       "groups": [{"name": "app.mail", "description": "Library group."}]}
    """.trimIndent())
    ModuleRootModificationUtil.addModuleLibrary(module, metadata.parent.parent.url)
  }

  override fun tearDown() {
    try {
      assertLayeredKeysMatchFlat(module)
    }
    finally {
      try {
        ModuleRootModificationUtil.updateModel(module) { model ->
          model.moduleLibraryTable.libraries.toList().forEach { model.moduleLibraryTable.removeLibrary(it) }
          buildDir?.let { dir -> model.contentEntries.filter { it.url == VfsUtil.pathToUrl(dir.toString()) }.forEach(model::removeContentEntry) }
        }
        buildDir?.let { FileUtil.delete(it) }
      }
      catch (e: Throwable) {
        addSuppressedException(e)
      }
      finally {
        super.tearDown()
      }
    }
  }

  private fun keys(): ConfigKeys = ConfigMetadataService.getInstance(project).keysFor(module)

  private fun mailProperties(): PsiFile = myFixture.addFileToProject("app/MailProperties.java", """
    package app;
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.mail")
    public class MailProperties {
      private String host;
      public void setHost(String host) {}
    }
  """.trimIndent())

  private fun edit(file: PsiFile, from: String, to: String) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val at = document.text.indexOf(from)
      document.replaceString(at, at + from.length, to)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  fun testOverlayEqualsFlatMergeOnConflicts() {
    fun p(name: String, type: String?, description: String?, default: String?, deprecated: Boolean = false) =
      ConfigProperty(name, type, description, default, null, if (deprecated) Deprecation("warning", null, null) else null)
    val top = ParsedMetadata(
      listOf(p("a.x", null, "top", null), p("a.x", "java.lang.String", null, null), p("z.new", "java.lang.Long", null, "1")),
      mapOf("a.x" to listOf(HintValue("t", null))),
      mapOf("a" to null, "b" to "top b", "new" to null))
    val middle = ParsedMetadata(
      listOf(p("a.x", "java.lang.Integer", "middle", "2", deprecated = true), p("m.only", null, null, null)),
      mapOf("a.x" to listOf(HintValue("m", "middle")), "m.only" to listOf(HintValue("v", null))),
      mapOf("a" to "middle a", "b" to "middle b", "c" to null))
    val bottom = ParsedMetadata(
      listOf(p("a.x", null, null, "3"), p("b.y", "java.lang.Boolean", "bottom", null), p("0.first", null, null, null)),
      mapOf("b.y" to listOf(HintValue("true", null))),
      mapOf("c" to "bottom c", "a" to "bottom a"))
    val flat = ConfigKeys(listOf(top, middle, bottom))
    val layered = ConfigKeys(listOf(bottom)).overlaidBy(middle).overlaidBy(top)
    assertEquals(flat.describe(), layered.describe())
    assertEquals(flat.childrenOf("").map { it.fullName }, layered.childrenOf("").map { it.fullName })
  }

  fun testJavaEditDoesNotRerunLibraryMerge() {
    val java = mailProperties()
    assertEquals("From the library.", keys().property("app.mail.host")!!.description)
    val before = ConfigMetadataService.libraryMerges.get()
    edit(java, "private String host;", "private String host; private int port; public void setPort(int p) {}")
    assertNotNull(keys().property("app.mail.port"))
    edit(java, "private int port; public void setPort(int p) {}", "private int timeout; public void setTimeout(int t) {}")
    assertNull(keys().property("app.mail.port"))
    assertEquals("int", keys().property("app.mail.timeout")!!.type)
    assertEquals(before, ConfigMetadataService.libraryMerges.get())
    assertLayeredKeysMatchFlat(module)
  }

  fun testAdditionalMetadataCreatedChangedAndDeleted() {
    mailProperties()
    assertNull(keys().property("app.extra"))
    val before = ConfigMetadataService.libraryMerges.get()
    val file = myFixture.addFileToProject("META-INF/additional-spring-configuration-metadata.json",
                                          """{"properties": [{"name": "app.extra", "type": "java.lang.String"}]}""").virtualFile
    assertEquals("java.lang.String", keys().property("app.extra")!!.type)
    WriteAction.run<Throwable> { VfsUtil.saveText(file, """{"properties": [{"name": "app.extra", "type": "java.lang.Integer"}]}""") }
    assertEquals("java.lang.Integer", keys().property("app.extra")!!.type)
    assertLayeredKeysMatchFlat(module)
    WriteAction.run<Throwable> { file.delete(this) }
    assertNull(keys().property("app.extra"))
    assertEquals(before, ConfigMetadataService.libraryMerges.get())
  }

  /** A build writes its metadata straight to disk, without the VFS; its keys must still show up on the next request. */
  fun testBuildOutputMetadataWrittenOnDiskShowsUp() {
    val dir = Files.createTempDirectory("boot-build").toRealPath()
    buildDir = dir
    Files.writeString(dir.resolve("build.gradle"), "")
    val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
    ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root) }
    assertNull(keys().property("app.built"))
    val before = ConfigMetadataService.libraryMerges.get()

    val output = dir.resolve("build/classes/java/main/META-INF/spring-configuration-metadata.json")
    Files.createDirectories(output.parent)
    Files.writeString(output, """{"properties": [{"name": "app.built", "type": "java.lang.String", "description": "Built."}]}""")
    buildFinished()
    assertEquals("Built.", keys().property("app.built")!!.description)
    assertLayeredKeysMatchFlat(module)

    Files.writeString(output, """{"properties": [{"name": "app.rebuilt", "type": "java.lang.String"}]}""")
    Files.setLastModifiedTime(output, FileTime.fromMillis(Files.getLastModifiedTime(output).toMillis() + 2000))
    buildFinished()
    assertNull(keys().property("app.built"))
    assertNotNull(keys().property("app.rebuilt"))

    Files.delete(output)
    buildFinished()
    assertNull(keys().property("app.rebuilt"))
    assertEquals(before, ConfigMetadataService.libraryMerges.get())
  }

  /** Completion and inspections ask for keys many times a second: the build output is not looked at on each request. */
  fun testRepeatedRequestsDoNotCheckBuildOutputOnDisk() {
    val dir = Files.createTempDirectory("boot-build").toRealPath()
    buildDir = dir
    Files.writeString(dir.resolve("build.gradle"), "")
    val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
    ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root) }
    keys()
    val checks = ConfigMetadataService.diskChecks.get()
    repeat(10) { keys() }
    assertEquals(checks, ConfigMetadataService.diskChecks.get())

    val output = dir.resolve("target/classes/META-INF/spring-configuration-metadata.json")
    Files.createDirectories(output.parent)
    Files.writeString(output, """{"properties": [{"name": "app.built", "type": "java.lang.String"}]}""")
    buildFinished()
    assertNotNull(keys().property("app.built"))
    assertEquals(checks + 1, ConfigMetadataService.diskChecks.get())
  }

  /** What BootBuildListener does when a build in the IDE finishes. */
  private fun buildFinished() = ConfigMetadataService.getInstance(project).buildFinished()
}
