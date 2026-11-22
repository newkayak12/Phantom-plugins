// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
// Modified by Phantom contributors, 2026; see the Git history of https://github.com/newkayak12/Phantom-plugins.
package org.jetbrains.plugins.groovy.uast

import com.intellij.platform.uast.testFramework.common.RenderLogTestBase
import com.intellij.platform.uast.testFramework.env.AbstractUastFixtureTest

abstract class AbstractGroovyUastTest : AbstractUastFixtureTest() {
  public override fun getTestDataPath(): String =
    // Phantom-plugins: copied to testData/uast in the plugin folder
    java.io.File("testData/uast").absolutePath
}

abstract class AbstractGroovyRenderLogTest : AbstractGroovyUastTest(), RenderLogTestBase