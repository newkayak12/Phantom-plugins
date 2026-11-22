// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.editorconfig.core

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.ec4j.core.model.Property
import org.editorconfig.plugincomponents.EditorConfigPropertiesService

class EditorConfigPropertiesServiceTest : BasePlatformTestCase() {
  fun testEmptyValueIsNotFailure() {
    val file = myFixture.configureByFile(".editorconfig")
    lateinit var props: Map<String, String>
    assertNoThrowable {
      props = EditorConfigPropertiesService.getInstance(myFixture.project).getProperties(file.virtualFile)
    }
    assertTrue("key" in props)
    assertTrue(props["key"]!!.isEmpty())
  }

  override fun getTestDataPath(): String =
    org.editorconfig.EditorConfigTestRoot.PATH + "/testData/org/editorconfig/core/settingsProviderComponent/" + getTestName(true)

  override fun isCommunity(): Boolean {
    return true
  }
}