// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.kotlin

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.phantom.spring.RealJdkWithKotlin
import dev.phantom.spring.SpringBeanNamesLookupTest
import dev.phantom.spring.SpringStubs

/** Kotlin beans: the lookup by name matches the full catalog, and defers to it for constants and type aliases. */
class KotlinSpringBeanNamesLookupTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdkWithKotlin

  override fun setUp() {
    super.setUp()
    SpringStubs.add(myFixture)
    SpringBeanNamesLookupTest.addDataStubs(myFixture)
  }

  fun testLiteralNamesMatchCatalog() {
    myFixture.addFileToProject("app/Beans.kt", """
      package app
      import org.springframework.context.annotation.Bean
      import org.springframework.context.annotation.Configuration
      import org.springframework.context.annotation.Profile
      import org.springframework.stereotype.Component
      import org.springframework.stereotype.Service

      @Component("clock") class SystemClock
      @Component @Profile("test") class FakeMailer

      @Configuration
      class Config {
          @Bean fun clock(): Any = Any()
          @Bean fun fakeMailer(): Any = Any()
          @Bean(name = ["named"]) fun x(): Any = Any()
          @Bean("arrayName", "alias") fun y(): Any = Any()
          @Bean(value = ["valueName"]) fun w(): Any = Any()
          @Bean fun shared(): Any = Any()
          fun notABean(): Any = Any()

          @Configuration
          class Nested {
              @Bean fun nestedBean(): Any = Any()
              @Bean("named") fun z(): Any = Any()
          }
      }

      @Service("shared") class SharedService
      @Component class Shared
      @Component annotation class UseCase(val value: String = "")
      @UseCase("useCaseBean") class PlaceOrder
      @Service(value = "useCaseBean") class OtherUseCase
      interface OrderRepository : org.springframework.data.repository.Repository<Any, Long>
      @org.springframework.boot.context.properties.ConfigurationProperties("app") class AppProperties
      @Component("app.dotted-bean") class Dotted1
      @Service("app.dotted-bean") class Dotted2
      @Service abstract class AbstractService
      class Mentions { val s = "shared clock named" }
    """.trimIndent())
    myFixture.addFileToProject("app/other/Repo.kt", "package app.other\n@org.springframework.stereotype.Component class OrderRepository")
    myFixture.addClass("package app; @org.springframework.stereotype.Component(\"useCaseBean\") public class JavaUseCase {}")
    SpringBeanNamesLookupTest.assertNamesMatchCatalog(project, expectLookup = true)
  }

  fun testConstantNameFallsBackToCatalog() {
    myFixture.addFileToProject("app/Beans.kt", """
      package app
      object Names { const val N = "constBean" }
      @org.springframework.stereotype.Service(Names.N) class C1
      @org.springframework.stereotype.Component("constBean") class C2
    """.trimIndent())
    SpringBeanNamesLookupTest.assertNamesMatchCatalog(project, expectLookup = false)
  }

  fun testTemplateNameFallsBackToCatalog() {
    myFixture.addFileToProject("app/Beans.kt", """
      package app
      const val PREFIX = "order"
      @org.springframework.stereotype.Service("${'$'}{PREFIX}Service") class C1
      @org.springframework.stereotype.Component class OrderService
    """.trimIndent())
    SpringBeanNamesLookupTest.assertNamesMatchCatalog(project, expectLookup = false)
  }

  fun testImportAliasFallsBackToCatalog() {
    myFixture.addFileToProject("app/Beans.kt", """
      package app
      import org.springframework.stereotype.Service as Svc
      const val N = "aliased"
      @Svc(N) class C1
      @org.springframework.stereotype.Component("aliased") class C2
    """.trimIndent())
    SpringBeanNamesLookupTest.assertNamesMatchCatalog(project, expectLookup = false)
  }

  fun testTypeAliasFallsBackToCatalog() {
    myFixture.addFileToProject("app/Aliases.kt", "package app\ntypealias Svc = org.springframework.stereotype.Service")
    myFixture.addFileToProject("app/Beans.kt", """
      package app
      @Svc("aliased") class C1
      @org.springframework.stereotype.Component("aliased") class C2
    """.trimIndent())
    SpringBeanNamesLookupTest.assertNamesMatchCatalog(project, expectLookup = false)
  }
}
