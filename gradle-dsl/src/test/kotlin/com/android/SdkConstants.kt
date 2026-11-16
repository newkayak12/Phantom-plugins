// Phantom: a stand-in for the Android Studio class of the same name, which the upstream tests use but which is not part of
// IntelliJ Community or any published artifact Phantom builds against. Only what these tests call, behaving the same.
package com.android

object SdkConstants {
  const val FN_BUILD_GRADLE: String = "build.gradle"
  const val FN_BUILD_GRADLE_KTS: String = "build.gradle.kts"
  const val FN_BUILD_GRADLE_DECLARATIVE: String = "build.gradle.dcl"
  const val FN_SETTINGS_GRADLE: String = "settings.gradle"
  const val FN_SETTINGS_GRADLE_KTS: String = "settings.gradle.kts"
  const val FN_SETTINGS_GRADLE_DECLARATIVE: String = "settings.gradle.dcl"
  const val FN_GRADLE_PROPERTIES: String = "gradle.properties"
}
