// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import dev.phantom.spring.RealJdk
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.core.SpringMvcEndpoints

/** The same endpoint discovery on Kotlin controllers: array literals, constants, interface mappings. */
class KotlinSpringMvcEndpointsTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    val web = "package org.springframework.web.bind.annotation;"
    val mapping = "String[] value() default {}; String[] path() default {};"
    listOf(
      "package org.springframework.stereotype; $retention public @interface Component { String value() default \"\"; }",
      "package org.springframework.stereotype; $retention @Component public @interface Controller { String value() default \"\"; }",
      "$web $retention @org.springframework.stereotype.Controller public @interface RestController { String value() default \"\"; }",
      "$web public enum RequestMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE }",
      "$web $retention public @interface RequestMapping { $mapping RequestMethod[] method() default {}; }",
      "$web $retention public @interface GetMapping { $mapping }",
      "$web $retention public @interface PostMapping { $mapping }",
    ).forEach { myFixture.addClass(it) }
  }

  private fun endpoints(): List<String> =
    SpringMvcEndpoints.all(project).map { "${it.methods.ifEmpty { listOf("ANY") }.joinToString("|")} ${it.path} ${it.controllerName}.${it.handlerName}" }

  fun testKotlinController() {
    myFixture.addFileToProject("app/ReservationController.kt", """
      package app
      import org.springframework.web.bind.annotation.*

      const val BASE = "/reservations"

      @RestController
      @RequestMapping(BASE)
      class ReservationController {
        @GetMapping("/{id}")
        fun get(@Suppress("UNUSED_PARAMETER") id: Long): String = ""

        @PostMapping
        fun create(): String = ""

        @RequestMapping(value = ["/a", "b"], method = [RequestMethod.PUT])
        fun put() {}

        fun notMapped() {}
      }
    """.trimIndent())
    assertEquals(listOf(
      "POST /reservations ReservationController.create",
      "PUT /reservations/a ReservationController.put",
      "PUT /reservations/b ReservationController.put",
      "GET /reservations/{id} ReservationController.get",
    ), endpoints())
  }

  fun testKotlinInterfaceImplementedByController() {
    myFixture.addFileToProject("app/Api.kt", """
      package app
      import org.springframework.web.bind.annotation.*
      @RequestMapping("/orders")
      interface OrdersApi {
        @GetMapping("/{id}")
        fun get(): String
      }
      @RestController
      class OrdersController : OrdersApi {
        override fun get(): String = ""
      }
      class Plain {
        @GetMapping("/hidden") fun hidden() {}
      }
    """.trimIndent())
    assertEquals(listOf("GET /orders/{id} OrdersController.get"), endpoints())
  }
}
