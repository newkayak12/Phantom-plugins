// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.gradle.java

import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.platform.externalSystem.testFramework.ExternalSystemImportingTestCase
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.tooling.VersionMatcherRule
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleUtil

/**
 * Imports real Gradle projects through the IDE's Gradle sync, on the published external system test framework.
 * IntelliJ's own base (GradleImportingTestCase) builds on test sources IntelliJ doesn't publish, so gradle-core leaves
 * it out; this one covers the import path with the same Gradle version its tests use (VersionMatcherRule).
 */
abstract class PhantomGradleImportingTestCase : ExternalSystemImportingTestCase() {

  private val projectSettings = GradleProjectSettings().apply {
    distributionType = DistributionType.DEFAULT_WRAPPED
    // The IDE's own runtime (JDK 25), which the Gradle version below runs on
    gradleJvm = ExternalSystemJdkUtil.USE_INTERNAL_JAVA
  }

  override fun getTestsTempDir(): String = "gradleImportTests"

  override fun getExternalSystemConfigFileName(): String = GradleConstants.DEFAULT_SCRIPT_NAME

  override fun getExternalSystemId(): ProjectSystemId = GradleConstants.SYSTEM_ID

  override fun getCurrentExternalProjectSettings(): ExternalProjectSettings = projectSettings

  /** Writes the wrapper properties so the import runs the Gradle version the other tests download. */
  protected fun useTestGradleVersion() {
    val uri = GradleUtil.getWrapperDistributionUri(GradleVersion.version(VersionMatcherRule.BASE_GRADLE_VERSION))
    createProjectSubFile("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=" + uri.toString().replace(":", "\\:") + "\n")
  }
}
