// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** The Spring config and entity caches follow the files they read, and are not recomputed by unrelated edits. */
class JpaCacheDepsTest : LightJavaCodeInsightFixtureTestCase() {
  companion object {
    init {
      System.setProperty("idea.load.plugins.id", "dev.phantom.db,org.jetbrains.kotlin")
    }

    const val STANDARD = "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl"
    const val SNAKE = "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
  }

  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package jakarta.persistence; public @interface Entity { String name() default \"\"; }")
    myFixture.addClass("package org.springframework.boot.autoconfigure; public @interface SpringBootApplication {}")
  }

  private fun yaml(strategy: String) = "spring:\n  jpa:\n    hibernate:\n      naming:\n        physical-strategy: $strategy\n"

  private fun physical(): String? = JpaSpringConfig.get(project).physical

  private fun markers(): List<String> {
    myFixture.doHighlighting()
    return DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
      .filter { it.lineMarkerTooltip?.startsWith("Mapped by") == true }
      .map { "${it.element!!.text} -> ${it.lineMarkerTooltip}" }
  }

  fun testExternalChangeUpdatesNaming() {
    val file = myFixture.addFileToProject("application.yml", yaml(STANDARD)).virtualFile
    assertTrue(JpaSchemaModel.naming(project)!!.standard)
    // changed on disk, not open in an editor
    WriteAction.run<Throwable> { VfsUtil.saveText(file, yaml(SNAKE)) }
    assertFalse(JpaSchemaModel.naming(project)!!.standard)
  }

  fun testUnsavedEditUpdatesConfig() {
    val file = myFixture.addFileToProject("application.properties", "spring.jpa.hibernate.naming.physical-strategy=$STANDARD\n").virtualFile
    assertEquals(STANDARD, physical())
    myFixture.configureFromExistingVirtualFile(file)
    val document = myFixture.editor.document
    WriteCommandAction.runWriteCommandAction(project) { document.setText("spring.jpa.hibernate.naming.physical-strategy=$SNAKE\n") }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(SNAKE, physical())
  }

  fun testConfigFileCreatedAndDeleted() {
    assertNull(physical())
    val file = myFixture.addFileToProject("application.properties", "spring.jpa.hibernate.naming.physical-strategy=$STANDARD\n").virtualFile
    assertEquals(STANDARD, physical())
    WriteAction.run<Throwable> { file.delete(this) }
    assertNull(physical())
  }

  fun testJavaEditDoesNotRereadConfig() {
    myFixture.addFileToProject("application.properties", "spring.jpa.hibernate.naming.physical-strategy=$STANDARD\n")
    myFixture.configureByText("A.java", "public class A { <caret> }")
    assertEquals(STANDARD, physical())
    val computed = JpaSpringConfig.computeCount.get()
    val psi = PsiModificationTracker.getInstance(project).modificationCount
    myFixture.type("int x;")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(PsiModificationTracker.getInstance(project).modificationCount > psi)
    assertEquals(STANDARD, physical())
    assertEquals(computed, JpaSpringConfig.computeCount.get())
  }

  fun testSqlEditDoesNotRescanEntities() {
    myFixture.addClass("package app; import jakarta.persistence.*; @Entity public class RoomBooking { Long id; }")
    myFixture.configureByText("V1__init.sql", "CREATE TABLE room_booking (id BIGINT);\n<caret>")
    assertEquals(listOf("room_booking -> Mapped by RoomBooking"), markers())
    val scans = entityScanCount.get()
    myFixture.type("SELECT 1;")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(listOf("room_booking -> Mapped by RoomBooking"), markers())
    assertEquals(scans, entityScanCount.get())
  }

  fun testNewEntityAppearsInSqlMarker() {
    myFixture.configureByText("V2__guest.sql", "CREATE TABLE guest_room (id BIGINT);\n")
    assertEmpty(markers())
    myFixture.addClass("package app; import jakarta.persistence.*; @Entity public class GuestRoom { Long id; }")
    assertEquals(listOf("guest_room -> Mapped by GuestRoom"), markers())
  }
}
