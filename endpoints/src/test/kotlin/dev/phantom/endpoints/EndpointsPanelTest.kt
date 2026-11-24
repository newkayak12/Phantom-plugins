// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.endpoints

import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EndpointsPanelTest : BasePlatformTestCase() {
  fun testCollectsAgainOnlyWhenTheStampMoved() {
    var stamp = listOf(1L, 7L)
    val panel = EndpointsPanel(project, testRootDisposable) { stamp }
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    assertFalse("a PSI change no provider counts does not collect again", panel.refreshIfChanged())

    stamp = listOf(2L, 7L)
    assertTrue(panel.refreshIfChanged())
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    assertFalse(panel.refreshIfChanged())

    stamp = listOf(2L, 8L)
    assertTrue("the project roots moved", panel.refreshIfChanged())
  }

  fun testStampHasOneCountPerProviderAndTheRoots() {
    val expected = com.intellij.microservices.endpoints.EndpointsProvider.getAllProviders().size + 1
    assertEquals(expected, endpointsStamp(project).size)
  }
}
