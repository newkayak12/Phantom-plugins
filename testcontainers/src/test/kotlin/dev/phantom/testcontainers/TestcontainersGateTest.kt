// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.testcontainers

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** The inspections do nothing, not even resolve calls, in modules without Testcontainers. */
class TestcontainersGateTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  fun testOnlyModulesWithTestcontainersAreInspected() {
    val file = myFixture.configureByText("ATest.java", "class ATest {}")
    assertFalse(Tc.isTestcontainersModule(file))
    myFixture.addClass("package org.testcontainers.lifecycle; public interface Startable { void start(); }")
    assertTrue(Tc.isTestcontainersModule(file))
  }
}
