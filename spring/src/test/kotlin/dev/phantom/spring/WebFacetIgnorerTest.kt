// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.facet.FacetManagerBase
import com.intellij.facet.impl.invalid.InvalidFacetManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.jps.model.serialization.facet.FacetState

/**
 * A "web" facet written by IntelliJ IDEA Ultimate opens without the "facets cannot be loaded" notification, whatever its
 * name; unknown facets of other types are still reported.
 */
class WebFacetIgnorerTest : BasePlatformTestCase() {
  fun testUltimateWebFacetIsIgnored() {
    assertTrue(isIgnored(type = "web", name = "Web (renamed)"))
  }

  fun testOtherUnknownFacetIsStillReported() {
    assertFalse(isIgnored(type = "someOtherPluginFacet", name = "Web"))
  }

  private fun isIgnored(type: String, name: String): Boolean {
    val state = FacetState().apply {
      facetType = type
      this.name = name
    }
    val facet = FacetManagerBase.createInvalidFacet(module, state, null, "Unknown facet type: '$type'", true, false)
    try {
      return InvalidFacetManager.getInstance(project).isIgnored(facet)
    }
    finally {
      Disposer.dispose(facet)
    }
  }
}
