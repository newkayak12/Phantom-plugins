// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ai

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.ai.core.AiState
import dev.phantom.ai.core.GhostModeSuggestion

/** The Ghost Mode balloon shows only where Phantom has the key, while it is off, once per IDE run and never after "Don't Ask Again". */
class GhostModeSuggestionInIdeTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    GhostModeSuggestion.resetForTests()
    AiState.getInstance().state.ghostModeDeclined = false
  }

  override fun tearDown() {
    try {
      AiState.getInstance().state.ghostModeDeclined = false
      GhostModeSuggestion.resetForTests()
    } finally {
      super.tearDown()
    }
  }

  // The IntelliJ test platform does not register phantom.ghost; a system property, which RegistryValue reads before the
  // bundle, stands in for Phantom's key.
  private fun phantomKey() {
    System.setProperty(GhostModeSuggestion.KEY, "false")
    Disposer.register(testRootDisposable) { System.clearProperty(GhostModeSuggestion.KEY) }
    Registry.get(GhostModeSuggestion.KEY).setValue(false, testRootDisposable)
  }

  fun testNotOfferedWithoutPhantomKey() {
    assertFalse(GhostModeSuggestion.shouldOffer())
  }

  fun testOfferedOnceAndTurnOnSetsKey() {
    phantomKey()
    assertTrue(GhostModeSuggestion.shouldOffer())
    GhostModeSuggestion.offer(project)
    assertFalse("once per IDE run", GhostModeSuggestion.shouldOffer())
    GhostModeSuggestion.turnOn()
    assertTrue(Registry.`is`(GhostModeSuggestion.KEY, false))
  }

  fun testNotOfferedWhenOnOrDeclined() {
    phantomKey()
    Registry.get(GhostModeSuggestion.KEY).setValue(true)
    assertFalse(GhostModeSuggestion.shouldOffer())
    Registry.get(GhostModeSuggestion.KEY).setValue(false)
    GhostModeSuggestion.decline()
    assertFalse(GhostModeSuggestion.shouldOffer())
  }
}
