// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package com.intellij.grazie.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageToolDescriptorUrlTest {
  @Test
  fun `language bundles come from the repository's GitHub release, not from JetBrains' servers`() {
    assertEquals(
      "https://github.com/newkayak12/Phantom-plugins/releases/download/languagetool-6.8.27/de-6.8.27.jar",
      LanguageToolDescriptor.GERMAN.url,
    )
    for (descriptor in LanguageToolDescriptor.entries) {
      assertEquals("languagetool-6.8.27/${descriptor.storageName}", descriptor.url.substringAfter("/releases/download/"))
    }
  }
}
