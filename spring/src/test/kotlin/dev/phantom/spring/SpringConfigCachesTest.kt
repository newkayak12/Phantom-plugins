// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.KotlinOpenPlugins
import dev.phantom.spring.core.SpringConfigKeys
import dev.phantom.spring.core.ide.JdkProxyMode
import dev.phantom.spring.core.ide.SpringApiVersioning
import dev.phantom.spring.core.ide.SpringUnknownProfileInspection
import dev.phantom.spring.messaging.ConfigPlaceholders
import java.util.concurrent.atomic.AtomicInteger

/**
 * The caches of config and build files are rebuilt when those files change (on disk, in the editor, created or
 * deleted), and not on edits of Java or Kotlin code.
 */
class SpringConfigCachesTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  private val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
  }

  private fun keys() = SpringConfigKeys.get(project)

  private fun saveOnDisk(file: VirtualFile, text: String) = WriteAction.run<Throwable> { VfsUtil.saveText(file, text) }

  private fun delete(file: VirtualFile) = WriteAction.run<Throwable> { file.delete(this) }

  /**
   * Reads, types into a code file, reads again: the counter of the cache's reads must not move. The PSI change must
   * count, or the test would prove nothing.
   */
  private fun assertCodeEditKeeps(counter: AtomicInteger, read: () -> Unit, fileName: String, text: String, typed: String) {
    myFixture.configureByText(fileName, text)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    read()
    val modifications = PsiModificationTracker.getInstance(project).modificationCount
    val before = counter.get()
    myFixture.type(typed)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(PsiModificationTracker.getInstance(project).modificationCount > modifications)
    read()
    assertEquals(before, counter.get())
  }

  fun testConfigKeysExternalChange() {
    val file = myFixture.addFileToProject("application.properties", "app.first=1\n").virtualFile
    assertTrue(keys().contains("app.first"))
    saveOnDisk(file, "app.second=2\n")
    assertTrue(keys().contains("app.second"))
    assertFalse(keys().contains("app.first"))
  }

  fun testConfigKeysUnsavedEdit() {
    val file = myFixture.addFileToProject("application.yml", "app:\n  first: 1\n").virtualFile
    assertTrue(keys().contains("app.first"))
    myFixture.configureFromExistingVirtualFile(file)
    val document = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project) { document.setText("app:\n  second: 2\n") }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(keys().contains("app.second"))
    assertFalse(keys().contains("app.first"))
  }

  fun testConfigKeysSurviveCodeEdits() {
    myFixture.addFileToProject("application.properties", "app.first=1\n")
    assertCodeEditKeeps(SpringConfigKeys.computeCount, { keys() }, "Foo.java", "class Foo { <caret> }", "int x;")
    assertCodeEditKeeps(SpringConfigKeys.computeCount, { keys() }, "Bar.kt", "class Bar { <caret> }", "val x = 1")
    assertTrue(keys().contains("app.first"))
  }

  fun testConfigKeysFileCreatedAndDeleted() {
    myFixture.addFileToProject("application.properties", "app.first=1\n")
    assertFalse(keys().contains("app.local"))
    val local = myFixture.addFileToProject("application-local.yml", "app:\n  local: 1\n").virtualFile
    assertTrue(keys().contains("app.local"))
    delete(local)
    assertFalse(keys().contains("app.local"))
  }

  fun testUnknownProfilesFollowConfigFiles() {
    myFixture.addClass("package org.springframework.test.context; $runtime public @interface ActiveProfiles { String[] value() default {}; }")
    val file = myFixture.addFileToProject("application.yml", "spring:\n  profiles:\n    active: local\n").virtualFile
    myFixture.configureByText("Beans.java", """
      package app;
      @org.springframework.context.annotation.Profile("cloud") class Cloud {}
    """.trimIndent())
    myFixture.enableInspections(SpringUnknownProfileInspection())
    fun problems(): List<String> {
      // a change in another file does not always make the daemon run again on Beans.java; this tests the cache, not that
      DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file, "config file changed")
      return myFixture.doHighlighting().filter { it.inspectionToolId != null }.mapNotNull { it.description }
    }
    assertSize(1, problems())
    saveOnDisk(file, "spring:\n  profiles:\n    active: cloud\n")
    assertEmpty(problems())
    saveOnDisk(file, "spring:\n  profiles:\n    active: local\n")
    myFixture.addFileToProject("application-cloud.yml", "x: 1\n")
    assertEmpty(problems())
  }

  fun testJdkProxyPropertyFollowsConfigFiles() {
    myFixture.addClass("package org.springframework.boot.autoconfigure; $runtime public @interface SpringBootApplication {}")
    val file = myFixture.addFileToProject("application.yml", "spring:\n  aop:\n    proxy-target-class: true\n").virtualFile
    assertFalse(JdkProxyMode.isJdkProxying(project))
    saveOnDisk(file, "spring:\n  aop:\n    proxy-target-class: false\n")
    assertTrue(JdkProxyMode.isJdkProxying(project))
    delete(file)
    assertFalse(JdkProxyMode.isJdkProxying(project))
    myFixture.addFileToProject("application.properties", "spring.aop.proxy-target-class=false\n")
    assertTrue(JdkProxyMode.isJdkProxying(project))
  }

  fun testApiVersionPropertyFollowsConfigFiles() {
    val file = myFixture.addFileToProject("application.properties", "server.port=8080\n").virtualFile
    assertFalse(SpringApiVersioning.isConfigured(project))
    saveOnDisk(file, "spring.mvc.apiversion.default=1.0\n")
    assertTrue(SpringApiVersioning.isConfigured(project))
  }

  fun testPlaceholdersExternalChange() {
    val file = myFixture.addFileToProject("application.properties", "app.topic=first\n").virtualFile
    assertEquals("first", ConfigPlaceholders.properties(project)["app.topic"])
    saveOnDisk(file, "app.topic=second\n")
    assertEquals("second", ConfigPlaceholders.properties(project)["app.topic"])
  }

  fun testPlaceholdersUnsavedEdit() {
    // an open document is read before the file on disk: an editor edit counts once committed
    val file = myFixture.addFileToProject("application.yml", "app:\n  topic: first\n").virtualFile
    assertEquals("first", ConfigPlaceholders.properties(project)["app.topic"])
    myFixture.configureFromExistingVirtualFile(file)
    val document = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project) { document.setText("app:\n  topic: second\n") }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    assertEquals("second", ConfigPlaceholders.properties(project)["app.topic"])
  }

  fun testReadYaml() {
    val yaml = """
      app:
        topics:
          order: "order.placed" # inline comment
          'quoted.key': audit
        list:
          - a
          - b
        block: |
          multi
      # a comment
      other: 1
      ---
      second:
        doc: two
    """.trimIndent()
    assertEquals(mapOf("app.topics.order" to "order.placed", "app.topics.quoted.key" to "audit", "other" to "1", "second.doc" to "two"),
                 ConfigPlaceholders.readYaml(yaml))
  }

  fun testJdkProxyModeSurvivesBodyEdits() {
    assertCodeEditKeeps(JdkProxyMode.codeModeComputes, { JdkProxyMode.isJdkProxying(project) }, "Foo.java",
                        "class Foo { void m() { <caret> } }", "int x = 1;")
  }

  fun testPlaceholdersSurviveCodeEdits() {
    myFixture.addFileToProject("application.properties", "app.topic=first\n")
    assertCodeEditKeeps(ConfigPlaceholders.readCount, { ConfigPlaceholders.properties(project) }, "Foo.java", "class Foo { <caret> }", "int x;")
  }

  fun testPlaceholdersFileCreatedAndDeleted() {
    myFixture.addFileToProject("application.properties", "app.topic=first\n")
    assertNull(ConfigPlaceholders.properties(project)["app.audit"])
    val local = myFixture.addFileToProject("application-local.properties", "app.audit=audit\n").virtualFile
    assertEquals("audit", ConfigPlaceholders.properties(project)["app.audit"])
    delete(local)
    assertNull(ConfigPlaceholders.properties(project)["app.audit"])
  }

  fun testKotlinPluginSettingsExternalChange() {
    val file = myFixture.addFileToProject("build.gradle.kts", "plugins { java }\n").virtualFile
    assertFalse(KotlinOpenPlugins.settings(project).springPlugin)
    saveOnDisk(file, "plugins { kotlin(\"plugin.spring\") }\nallOpen { annotation(\"app.Open\") }\n")
    assertTrue(KotlinOpenPlugins.settings(project).springPlugin)
    assertEquals(setOf("app.Open"), KotlinOpenPlugins.settings(project).allOpenAnnotations)
  }

  fun testKotlinPluginSettingsSurviveKotlinEdits() {
    myFixture.addFileToProject("build.gradle.kts", "plugins { kotlin(\"plugin.spring\") }\n")
    assertCodeEditKeeps(KotlinOpenPlugins.readCount, { KotlinOpenPlugins.settings(project) }, "Bar.kt", "class Bar { <caret> }", "val x = 1")
    assertTrue(KotlinOpenPlugins.settings(project).springPlugin)
  }

  fun testKotlinPluginSettingsFileCreatedAndDeleted() {
    assertFalse(KotlinOpenPlugins.settings(project).springPlugin)
    val pom = myFixture.addFileToProject("sub/pom.xml", "<plugin>spring</plugin>").virtualFile
    assertTrue(KotlinOpenPlugins.settings(project).springPlugin)
    delete(pom)
    assertFalse(KotlinOpenPlugins.settings(project).springPlugin)
  }
}
