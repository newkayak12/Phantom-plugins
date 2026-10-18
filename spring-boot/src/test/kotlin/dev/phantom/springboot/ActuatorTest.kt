// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot

import com.google.gson.JsonParser
import com.intellij.diagnostic.logging.AdditionalTabComponent
import com.intellij.execution.configurations.AdditionalTabComponentManager
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.phantom.springboot.run.ActuatorAddress
import dev.phantom.springboot.run.ActuatorJson
import dev.phantom.springboot.run.ActuatorTab
import dev.phantom.springboot.run.SpringBootConfigurationType
import dev.phantom.springboot.run.SpringBootRunConfiguration

class ActuatorTest : BasePlatformTestCase() {
  fun testAddressFromTheStartupLog() {
    val boot3 = ActuatorAddress.parse("2026-10-18 INFO 1 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/api'")
    assertEquals(ActuatorAddress.Server(8080, "http", "api"), boot3)
    assertEquals("http://localhost:8080/api/actuator", ActuatorAddress.baseUrl(listOf(boot3!!)))
    val boot2 = ActuatorAddress.parse("Tomcat started on port(s): 9090 (https) with context path ''")
    assertEquals(ActuatorAddress.Server(9090, "https", ""), boot2)
    assertEquals(ActuatorAddress.Server(8080, "http", ""), ActuatorAddress.parse("Netty started on port 8080 (http)"))
    // a separate management server is where the actuator lives
    val management = ActuatorAddress.Server(8081, "http", "")
    assertEquals("http://localhost:8081/actuator", ActuatorAddress.baseUrl(listOf(boot3, management)))
    assertNull(ActuatorAddress.parse("Started ReservationApplicationKt in 3.2 seconds"))
  }

  fun testBeansMappingsEnvAndHealth() {
    val beans = ActuatorJson.beans(JsonParser.parseString("""
      {"contexts":{"application":{"beans":{
        "userService":{"aliases":[],"scope":"singleton","type":"shop.UserService","dependencies":["userRepository","clock"]},
        "clock":{"scope":"singleton","type":"java.time.Clock","dependencies":[]}}}}}
    """))
    assertEquals(listOf("clock", "userService"), beans.map { it.name })
    assertEquals("userRepository, clock", beans[1].dependencies)

    val mappings = ActuatorJson.mappings(JsonParser.parseString("""
      {"contexts":{"application":{"mappings":{"dispatcherServlets":{"dispatcherServlet":[
        {"handler":"shop.UserController#get(Long)","predicate":"{GET [/users/{id}]}",
         "details":{"handlerMethod":{"className":"shop.UserController","name":"get"},
                    "requestMappingConditions":{"methods":["GET"],"patterns":["/users/{id}"]}}},
        {"handler":"ResourceHttpRequestHandler [classpath [static/]]","predicate":"/**"}]}}}}}
    """))
    assertEquals(listOf(ActuatorJson.Mapping("", "/**", "ResourceHttpRequestHandler [classpath [static/]]"),
                        ActuatorJson.Mapping("GET", "/users/{id}", "UserController.get")), mappings)

    val env = ActuatorJson.environment(JsonParser.parseString("""
      {"activeProfiles":["temporary"],"propertySources":[
        {"name":"systemProperties","properties":{"server.port":{"value":"9000"}}},
        {"name":"Config resource 'class path resource [application.yaml]'","properties":{"server.port":{"value":8080,"origin":"x"},"spring.application.name":{"value":"shop"}}}]}
    """))
    assertEquals(listOf(ActuatorJson.Property("server.port", "9000", "systemProperties"),
                        ActuatorJson.Property("spring.application.name", "shop", "Config resource 'class path resource [application.yaml]'")), env)

    val (status, lines) = ActuatorJson.health(JsonParser.parseString("""
      {"status":"DOWN","components":{"db":{"status":"UP","details":{"database":"MySQL"}},"redis":{"status":"DOWN"}}}
    """))
    assertEquals("DOWN", status)
    assertEquals(listOf("db: UP (MySQL)", "redis: DOWN"), lines)
  }

  fun testSpringBootRunsGetAnActuatorTab() {
    val configuration = SpringBootRunConfiguration("app", project, SpringBootConfigurationType.getInstance().factory)
    val added = ArrayList<AdditionalTabComponent>()
    configuration.createAdditionalTabComponents(object : AdditionalTabComponentManager {
      override fun addAdditionalTabComponent(component: AdditionalTabComponent, id: String) { added.add(component) }
      override fun removeAdditionalTabComponent(component: AdditionalTabComponent) {}
    }, NopProcessHandler())
    try {
      assertEquals(listOf("Actuator"), added.map { it.tabTitle })
      assertTrue(added.single() is ActuatorTab)
    }
    finally {
      added.forEach { Disposer.dispose(it) }
    }
  }
}
