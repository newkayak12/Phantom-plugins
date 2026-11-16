// Phantom: a stand-in for Android Studio's intellij.android.gradle.declarative.lang.flags (not part of IntelliJ
// Community). This plugin has no Declarative Gradle support (intellij.android.gradle.dsl.declarative is left out, see
// plugin.xml), so the flag stays off whatever a test asks, and the tests' Declarative (.gradle.dcl) variants are skipped.
package com.android.tools.idea.gradle.dcl.lang.flags

import org.jetbrains.annotations.TestOnly

object DeclarativeIdeSupport {
  @JvmStatic
  fun isEnabled(): Boolean = false

  @TestOnly
  @JvmStatic
  fun override(@Suppress("UNUSED_PARAMETER") value: Boolean) {
  }

  @TestOnly
  @JvmStatic
  fun clearOverride() {
  }
}
