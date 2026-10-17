// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor

/** Light fixtures with the JDK running the tests: the mock JDK lives only in the IntelliJ monorepo. */
object RealJdk : DefaultLightProjectDescriptor() {
  override fun getSdk(): Sdk = JavaAwareProjectJdkTableImpl.getInstanceEx().internalJdk
}
