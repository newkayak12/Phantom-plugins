// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springcloud

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FeignNavigationTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = JAVA_LATEST

  override fun setUp() {
    super.setUp()
    myFixture.addClass("package org.springframework.cloud.openfeign; public @interface FeignClient { String name() default \"\"; String path() default \"\"; }")
    myFixture.addClass("package org.springframework.stereotype; public @interface Controller {}")
    myFixture.addClass("package org.springframework.web.bind.annotation; @org.springframework.stereotype.Controller public @interface RestController {}")
    myFixture.addClass("package org.springframework.web.bind.annotation; public enum RequestMethod { GET, POST, PUT, DELETE, PATCH }")
    myFixture.addClass(
      "package org.springframework.web.bind.annotation; public @interface RequestMapping " +
        "{ String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }",
    )
    for (m in listOf("Get", "Post", "Put", "Delete", "Patch")) {
      myFixture.addClass("package org.springframework.web.bind.annotation; public @interface ${m}Mapping { String[] value() default {}; String[] path() default {}; }")
    }
    myFixture.addClass(
      """
      package app;
      import org.springframework.web.bind.annotation.*;
      @RestController
      @RequestMapping("/api/users")
      public class UserController {
        static final String BY_ID = "/{userId:\\d+}";
        @GetMapping(BY_ID) public String find(long userId) { return null; }
        @RequestMapping(path = BY_ID, method = RequestMethod.DELETE) public void remove(long userId) {}
        @PostMapping public void create(String body) {}
        public void helper() {}
      }
      """.trimIndent(),
    )
    myFixture.addClass(
      """
      package app;
      import org.springframework.cloud.openfeign.FeignClient;
      import org.springframework.web.bind.annotation.*;
      @FeignClient(name = "users", path = "/api")
      public interface UserClient {
        @GetMapping("/users/{id}") String get(long id);
        @DeleteMapping("users/{id}/") void delete(long id);
        @PutMapping("/users/{id}") void update(long id);
      }
      """.trimIndent(),
    )
  }

  private fun method(c: String, name: String): PsiMethod = myFixture.findClass(c).findMethodsByName(name, false).single()

  fun testNormalize() {
    assertEquals("/api/users/{}", Feign.normalize("api//users/{userId:\\d+}/"))
    assertEquals("/", Feign.normalize(""))
  }

  fun testFeignMethodFindsControllerByMethodAndPath() {
    assertEquals(listOf("find"), Feign.controllersFor(method("app.UserClient", "get")).map { it.name })
    assertEquals(listOf("remove"), Feign.controllersFor(method("app.UserClient", "delete")).map { it.name })
    // No PUT endpoint in the controller.
    assertEmpty(Feign.controllersFor(method("app.UserClient", "update")))
  }

  fun testControllerMethodFindsFeignClients() {
    assertEquals(listOf("get"), Feign.feignClientsFor(method("app.UserController", "find")).map { it.name })
    assertEmpty(Feign.feignClientsFor(method("app.UserController", "create")))
    assertEmpty(Feign.feignClientsFor(method("app.UserController", "helper")))
  }

  fun testGuttersBothWays() {
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.UserClient").containingFile.virtualFile)
    assertEquals(listOf("find", "remove"), gutterTargets().sorted())
    myFixture.configureFromExistingVirtualFile(myFixture.findClass("app.UserController").containingFile.virtualFile)
    assertEquals(listOf("delete", "get"), gutterTargets().sorted())
  }

  fun testNoGuttersWithoutOpenFeign() {
    myFixture.configureByText("Plain.java", "interface Plain { @org.springframework.web.bind.annotation.GetMapping(\"/x\") void x(); }")
    assertEmpty(gutterTargets())
  }

  private fun gutterTargets(): List<String> = myFixture.findAllGutters().flatMap { g ->
    val info = (g as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo as? RelatedItemLineMarkerInfo<*> ?: return@flatMap emptyList()
    info.createGotoRelatedItems().mapNotNull { (it.element as? PsiMethod)?.name }
  }
}
