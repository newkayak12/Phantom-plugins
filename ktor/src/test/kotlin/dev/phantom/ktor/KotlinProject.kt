// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.ktor

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor

/** The JDK running the tests plus the Kotlin standard library from the IDE's Kotlin plugin. */
object KotlinProject : DefaultLightProjectDescriptor() {
  override fun getSdk(): Sdk = JavaAwareProjectJdkTableImpl.getInstanceEx().internalJdk

  override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
    super.configureModule(module, model, contentEntry)
    PsiTestUtil.addLibrary(model, "kotlin-stdlib", PathManager.getHomePath() + "/plugins/Kotlin/kotlinc/lib", "kotlin-stdlib.jar")
  }
}
