// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.validation

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.PsiTestUtil

/** [RealJdk] plus the Kotlin standard library shipped with the IDE, so `List` and `MutableList` resolve in Kotlin tests. */
object KotlinStdlibJdk : com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor() {
  override fun getSdk() = RealJdk.sdk

  override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
    super.configureModule(module, model, contentEntry)
    PsiTestUtil.addLibrary(model, "kotlin-stdlib", PathManager.getHomePath() + "/plugins/Kotlin/kotlinc/lib", "kotlin-stdlib.jar")
  }
}
