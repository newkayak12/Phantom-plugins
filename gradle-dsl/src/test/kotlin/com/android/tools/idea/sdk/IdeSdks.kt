// Phantom: a stand-in for the Android Studio class of the same name, which the upstream tests use but which is not part of
// IntelliJ Community or any published artifact Phantom builds against. Only what these tests call, behaving the same.
package com.android.tools.idea.sdk

import com.intellij.openapi.Disposable

object IdeSdks {
  /** Android Studio removes its own JDK table entries for the test; IntelliJ's test framework sets up none. */
  @JvmStatic
  fun removeJdksOn(@Suppress("UNUSED_PARAMETER") disposable: Disposable) {
  }
}
