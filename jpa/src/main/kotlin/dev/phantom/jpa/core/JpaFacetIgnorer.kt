// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.facet.impl.invalid.FacetIgnorer
import com.intellij.facet.impl.invalid.InvalidFacet

/**
 * IntelliJ IDEA Ultimate's JPA plugin keeps a facet of type "jpa" in each module's .iml. Phantom has no facet type with that
 * id, so FacetManagerBase.createInvalidFacet reports "Unknown facet type: 'jpa'" every time such a project opens. This only
 * silences that report: the facet stays an InvalidFacet, which writes its original configuration back unchanged, so
 * Ultimate still reads it. Matched by type rather than name, because the facet name is user-editable.
 */
internal class JpaFacetIgnorer : FacetIgnorer {
  override fun isIgnored(facet: InvalidFacet): Boolean = facet.configuration.facetState.facetType == "jpa"
}
