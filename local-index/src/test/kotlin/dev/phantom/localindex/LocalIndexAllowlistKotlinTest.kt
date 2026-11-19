// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localindex

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.indexing.FileBasedIndexExtension

/** Needs the Kotlin plugin; excluded in build.gradle.kts when the IDE does not bundle it. */
class LocalIndexAllowlistKotlinTest : LightJavaCodeInsightFixtureTestCase() {
  fun `test kotlin source indexes are accepted`() {
    val ids = listOf(
      "KotlinPackageSourcesMemberNamesIndex",
      "org.jetbrains.kotlin.idea.vfilefinder.KotlinPartialPackageNamesIndex",
      "KotlinTopLevelCallableByPackageShortNameIndex",
      "KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex",
    )
    for (id in ids) {
      val extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.single { it.name.name == id }
      assertTrue(id, LocalIndexAllowlist.accepts(extension))
    }
  }
}
