// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.facet.impl.invalid.FacetIgnorer
import com.intellij.facet.impl.invalid.InvalidFacet

/**
 * IntelliJ IDEA Ultimate's Java EE web support keeps a facet of type "web" (web.xml and web resource roots) in the .iml of
 * Spring web modules. Phantom has no facet type with that id, so FacetManagerBase.createInvalidFacet reports "Unknown
 * facet type: 'web'" every time such a project opens. This only silences that report: the facet stays an InvalidFacet,
 * which writes its original configuration back unchanged, so Ultimate still reads it. Matched by type rather than name,
 * because the facet name is user-editable.
 */
internal class WebFacetIgnorer : FacetIgnorer {
  override fun isIgnored(facet: InvalidFacet): Boolean = facet.configuration.facetState.facetType == "web"
}
