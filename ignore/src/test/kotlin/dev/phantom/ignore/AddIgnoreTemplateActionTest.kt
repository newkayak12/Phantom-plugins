// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ignore

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ignore.templates.AddIgnoreTemplateAction

class AddIgnoreTemplateActionTest : BasePlatformTestCase() {
  fun `test templates are appended to the file in the directory`() {
    val dir = myFixture.tempDirFixture.findOrCreateDir("p")
    val file = AddIgnoreTemplateAction.addTemplates(project, dir, ".dockerignore", listOf("A" to "a\n"))!!
    assertEquals("### A ###\na\n", FileDocumentManager.getInstance().getDocument(file)!!.text)
  }

  fun `test a read-only ignore file is left alone`() {
    val file = myFixture.addFileToProject("p/.gitignore", "x\n").virtualFile
    WriteAction.run<Exception> { file.isWritable = false }
    assertNull(AddIgnoreTemplateAction.addTemplates(project, file.parent, ".gitignore", listOf("A" to "a\n")))
    assertEquals("x\n", FileDocumentManager.getInstance().getDocument(file)!!.text)
  }

  fun `test the action is hidden inside a jar`() {
    val inJar = VfsUtil.findFileByURL(junit.framework.TestCase::class.java.getResource("TestCase.class")!!)!!
    assertTrue(inJar.fileSystem is JarFileSystem)
    val action = AddIgnoreTemplateAction()
    val event = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, inJar.parent).build())
    action.update(event)
    assertFalse(event.presentation.isEnabledAndVisible)
  }
}
