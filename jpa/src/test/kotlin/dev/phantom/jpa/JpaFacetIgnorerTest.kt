// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.facet.FacetManagerBase
import com.intellij.facet.impl.invalid.InvalidFacetManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.jps.model.serialization.facet.FacetState

/**
 * A "jpa" facet written by IntelliJ IDEA Ultimate opens without the "facets cannot be loaded" notification, whatever its
 * name; unknown facets of other types are still reported.
 */
class JpaFacetIgnorerTest : BasePlatformTestCase() {
  fun testUltimateJpaFacetIsIgnored() {
    assertTrue(isIgnored(type = "jpa", name = "Jpa (renamed)"))
  }

  fun testOtherUnknownFacetIsStillReported() {
    assertFalse(isIgnored(type = "someOtherPluginFacet", name = "Jpa"))
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
