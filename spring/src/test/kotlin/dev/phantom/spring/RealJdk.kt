// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor

/** Light fixtures with the JDK running the tests: the mock JDK lives only in the IntelliJ monorepo. */
object RealJdk : DefaultLightProjectDescriptor() {
  override fun getSdk(): Sdk = JavaAwareProjectJdkTableImpl.getInstanceEx().internalJdk
}

/** [RealJdk] plus the Kotlin standard library from the IDE's Kotlin plugin, for Kotlin code that calls `listOf` and friends. */
object RealJdkWithKotlin : DefaultLightProjectDescriptor() {
  override fun getSdk(): Sdk = RealJdk.sdk

  override fun configureModule(module: com.intellij.openapi.module.Module, model: com.intellij.openapi.roots.ModifiableRootModel,
                               contentEntry: com.intellij.openapi.roots.ContentEntry) {
    super.configureModule(module, model, contentEntry)
    val jar = com.intellij.openapi.application.PathManager.getHomePath() + "/plugins/Kotlin/kotlinc/lib"
    com.intellij.testFramework.PsiTestUtil.addLibrary(model, "kotlin-stdlib", jar, "kotlin-stdlib.jar")
  }
}
