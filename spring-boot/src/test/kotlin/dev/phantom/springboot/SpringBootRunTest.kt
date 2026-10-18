// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.JavaParameters
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.JDOMUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.springboot.run.SpringBootConfigurationType
import dev.phantom.springboot.run.SpringBootProfilesExtension
import dev.phantom.springboot.run.SpringBootRunConfiguration
import org.jdom.Element

class SpringBootRunTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    myFixture.addClass("""
      package org.springframework.boot.autoconfigure;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface SpringBootApplication {}
    """.trimIndent())
  }

  private fun configurationAt(element: PsiElement): SpringBootRunConfiguration? {
    val context = ConfigurationContext.getFromContext(SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(LangDataKeys.MODULE, module)
      .add(com.intellij.execution.Location.DATA_KEY, com.intellij.execution.PsiLocation.fromPsiElement(element))
      .build(), "")
    return context.configuration?.configuration as? SpringBootRunConfiguration
  }

  fun testRunOnAJavaApplicationCreatesASpringBootConfiguration() {
    val file = myFixture.configureByText("ShopApplication.java", """
      package shop;
      @org.springframework.boot.autoconfigure.SpringBootApplication
      public class ShopApplication { public static void ma<caret>in(String[] args) {} }
    """.trimIndent())
    val configuration = configurationAt(file.findElementAt(myFixture.caretOffset)!!)
    assertNotNull("the Spring Boot producer did not win over Application", configuration)
    assertEquals("shop.ShopApplication", configuration!!.mainClassName)
    assertEquals("ShopApplication", configuration.name)
    assertEquals("shop.ShopApplication", configuration.runClass)
  }

  fun testKotlinMainNextToTheApplicationClass() {
    val file = myFixture.configureByText("ReservationApplication.kt", """
      package com.reservation
      @org.springframework.boot.autoconfigure.SpringBootApplication
      class ReservationApplication
      fun ma<caret>in(args: Array<String>) {}
    """.trimIndent())
    val configuration = configurationAt(file.findElementAt(myFixture.caretOffset)!!)
    assertNotNull("no Spring Boot configuration for a Kotlin main", configuration)
    assertEquals("com.reservation.ReservationApplication", configuration!!.mainClassName)
    assertEquals("the file class runs", "com.reservation.ReservationApplicationKt", configuration.runClass)
    configuration.checkClass()
  }

  fun testReadsAndWritesIdeaUltimateConfigurations() {
    val xml = """
      <configuration name="ReservationApplication" type="SpringBootApplicationConfigurationType" factoryName="Spring Boot" nameIsGenerated="true">
        <option name="ACTIVE_PROFILES" value="temporary" />
        <module name="light_idea_test_case" />
        <option name="SPRING_BOOT_MAIN_CLASS" value="com.reservation.ReservationApplication" />
        <method v="2"><option name="Make" enabled="true" /></method>
      </configuration>
    """.trimIndent()
    val configuration = SpringBootRunConfiguration("", project, SpringBootConfigurationType.getInstance().factory)
    configuration.readExternal(JDOMUtil.load(xml))
    assertEquals("com.reservation.ReservationApplication", configuration.mainClassName)
    assertEquals("temporary", configuration.activeProfiles)

    val written = Element("configuration")
    configuration.writeExternal(written)
    val options = written.getChildren("option").associate { it.getAttributeValue("name") to it.getAttributeValue("value") }
    assertEquals("com.reservation.ReservationApplication", options["SPRING_BOOT_MAIN_CLASS"])
    assertEquals("temporary", options["ACTIVE_PROFILES"])
    assertFalse(options.containsKey("MAIN_CLASS_NAME"))
  }

  fun testActiveProfilesGoToTheJvm() {
    val configuration = SpringBootRunConfiguration("app", project, SpringBootConfigurationType.getInstance().factory)
    configuration.activeProfiles = "local, dev"
    val parameters = JavaParameters()
    SpringBootProfilesExtension().updateJavaParameters(configuration, parameters, null)
    assertEquals(listOf("-Dspring.profiles.active=local,dev"), parameters.vmParametersList.parameters)

    // a -D the user typed in VM options wins
    val own = JavaParameters().apply { vmParametersList.addProperty("spring.profiles.active", "prod") }
    SpringBootProfilesExtension().updateJavaParameters(configuration, own, null)
    assertEquals(listOf("-Dspring.profiles.active=prod"), own.vmParametersList.parameters)
  }

  fun testSpringBootConfigurationsShowInServices() {
    val type = SpringBootConfigurationType.getInstance()
    assertEquals("SpringBootApplicationConfigurationType", type.id)
    assertTrue(dev.phantom.springboot.run.SpringBootDashboardTypes().getDefaultTypeIds(project).contains(type.id))
  }
}
