// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.testcontainers

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class TestcontainersTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor() = RealJdk

  override fun setUp() {
    super.setUp()
    val runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
    myFixture.addClass("package org.testcontainers.lifecycle; public interface Startable { void start(); }")
    myFixture.addClass("package org.testcontainers.utility; public class DockerImageName { public DockerImageName(String n) {} public static DockerImageName parse(String n) { return null; } }")
    myFixture.addClass("package org.testcontainers.containers; public class GenericContainer<S> implements org.testcontainers.lifecycle.Startable { public GenericContainer(String image) {} public GenericContainer(org.testcontainers.utility.DockerImageName image) {} public void start() {} }")
    myFixture.addClass("package org.testcontainers.containers; public class PostgreSQLContainer<S> extends GenericContainer<S> { public PostgreSQLContainer(String image) { super(image); } }")
    myFixture.addClass("package org.testcontainers.containers; public class Network {}")
    myFixture.addClass("package org.testcontainers.junit.jupiter; $runtime public @interface Container {}")
    myFixture.addClass("package org.testcontainers.junit.jupiter; $runtime @java.lang.annotation.Inherited public @interface Testcontainers {}")
    myFixture.addClass("package org.springframework.test.context; $runtime public @interface DynamicPropertySource {}")
    myFixture.addClass("package org.springframework.test.context; public interface DynamicPropertyRegistry {}")
    myFixture.addClass("package org.springframework.boot.testcontainers.service.connection; $runtime public @interface ServiceConnection {}")
    myFixture.addClass("package org.junit.jupiter.api; $runtime public @interface TestInstance { Lifecycle value(); enum Lifecycle { PER_METHOD, PER_CLASS } }")
    myFixture.addClass("package app; @org.testcontainers.junit.jupiter.Testcontainers public @interface IntegrationTest {}")
    myFixture.enableInspections(
      ContainerNotStartedInspection(), ContainerPerTestInspection(), ContainerImageTagInspection(), SpringStaticRequiredInspection(),
    )
  }

  /** What these inspections report in [text], as `highlighted text: message`. */
  private fun problems(name: String, text: String): List<String> {
    myFixture.configureByText(name, text)
    val ids = setOf("PhantomContainerNotStarted", "PhantomContainerPerTest", "PhantomContainerImageTag", "PhantomSpringStaticRequired")
    return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
      .filter { it.inspectionToolId in ids }
      .map { "${text.substring(it.startOffset, it.endOffset)}: ${it.description}" }
      .sorted()
  }

  fun testAContainerWithoutTheExtensionNeverStarts() {
    assertEquals(
      listOf("db: This container never starts: ATest has no @Testcontainers, so @Container is not read"),
      problems("ATest.java", """
        import org.testcontainers.containers.*;
        import org.testcontainers.junit.jupiter.Container;
        class ATest { @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16"); }
      """.trimIndent()),
    )
  }

  fun testTheExtensionFromASuperclassOrAMetaAnnotationCounts() {
    myFixture.addClass("package app; @org.testcontainers.junit.jupiter.Testcontainers public abstract class BaseIt {}")
    val quiet = """
      package app;
      import org.testcontainers.containers.*;
      import org.testcontainers.junit.jupiter.Container;
      %s { @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16"); }
    """.trimIndent()
    assertEmpty(problems("ATest.java", quiet.format("class ATest extends BaseIt")))
    assertEmpty(problems("BTest.java", quiet.format("@IntegrationTest class BTest")))
  }

  fun testANetworkIsNotAContainer() {
    assertEquals(
      listOf("net: @Container needs something to start: Network is not a Startable (a container)"),
      problems("ATest.java", """
        import org.testcontainers.containers.*;
        @org.testcontainers.junit.jupiter.Testcontainers
        class ATest { @org.testcontainers.junit.jupiter.Container static Network net = new Network(); }
      """.trimIndent()),
    )
  }

  fun testAnInstanceContainerStartsForEveryTest() {
    assertEquals(
      listOf("db: A new container starts for every test; make it static to share one across the class"),
      problems("ATest.java", """
        import org.testcontainers.containers.*;
        @org.testcontainers.junit.jupiter.Testcontainers
        class ATest { @org.testcontainers.junit.jupiter.Container PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16"); }
      """.trimIndent()),
    )
    val fix = myFixture.getAllQuickFixes().single { it.text.contains("static") }
    myFixture.launchAction(fix)
    assertTrue(myFixture.editor.document.text.contains("static PostgreSQLContainer<?> db"))
  }

  fun testOneInstanceForAllTestsIsShared() {
    assertEmpty(problems("ATest.java", """
      import org.testcontainers.containers.*;
      import org.junit.jupiter.api.TestInstance;
      @org.testcontainers.junit.jupiter.Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
      class ATest { @org.testcontainers.junit.jupiter.Container PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16"); }
    """.trimIndent()))
  }

  fun testImagesNeedAVersion() {
    assertEquals(
      listOf(
        "\"localhost:5000/redis\": No tag: Docker pulls localhost:5000/redis:latest, which changes under the tests; pin a version",
        "\"postgres\": No tag: Docker pulls postgres:latest, which changes under the tests; pin a version",
        "\"redis:latest\": redis:latest changes whenever the image is pushed; pin a version",
      ),
      problems("Images.java", """
        import org.testcontainers.containers.*;
        import org.testcontainers.utility.DockerImageName;
        class Images {
          Object a = new PostgreSQLContainer<>("postgres");
          Object b = new GenericContainer<>(DockerImageName.parse("redis:latest"));
          Object c = new GenericContainer<>("localhost:5000/redis");
          Object d = new GenericContainer<>("postgres:16.4");
          Object e = new GenericContainer<>("postgres@sha256:abc");
        }
      """.trimIndent()),
    )
  }

  fun testSpringNeedsStaticMembers() {
    assertEquals(
      listOf(
        "db: Spring finds @ServiceConnection fields only when they are static (in Kotlin: in the companion object)",
        "props: Spring calls @DynamicPropertySource methods only when they are static (in Kotlin: @JvmStatic in the companion object)",
      ),
      problems("ATest.java", """
        import org.testcontainers.containers.*;
        import org.springframework.test.context.*;
        import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
        class ATest {
          @ServiceConnection PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16");
          @DynamicPropertySource void props(DynamicPropertyRegistry r) {}
          @DynamicPropertySource static void ok(DynamicPropertyRegistry r) {}
        }
      """.trimIndent()),
    )
  }

  fun testKotlinCompanionMembersAreStatic() {
    assertEquals(
      listOf("props: Spring calls @DynamicPropertySource methods only when they are static (in Kotlin: @JvmStatic in the companion object)"),
      problems("ATest.kt", """
        import org.testcontainers.containers.PostgreSQLContainer
        import org.testcontainers.junit.jupiter.Container
        import org.testcontainers.junit.jupiter.Testcontainers
        import org.springframework.test.context.*

        @Testcontainers
        class ATest {
          companion object {
            @Container val db = PostgreSQLContainer<Nothing>("postgres:16")
            @JvmStatic @DynamicPropertySource fun ok(r: DynamicPropertyRegistry) {}
          }
          @DynamicPropertySource fun props(r: DynamicPropertyRegistry) {}
        }
      """.trimIndent()),
    )
  }
}
