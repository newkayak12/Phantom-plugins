// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.gradle.java

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType

class GradleJavaImportTest : PhantomGradleImportingTestCase() {

  override fun setUp() {
    super.setUp()
    useTestGradleVersion()
  }

  fun `test single-module project imports its source sets, source roots and a library`() {
    createProjectSubFile("settings.gradle", "rootProject.name = 'project'\n")
    createProjectSubFile("src/main/java/demo/Main.java", "package demo; public class Main {}\n")
    createProjectSubFile("src/test/java/demo/MainTest.java", "package demo; public class MainTest {}\n")
    createProjectConfig(
      """
      plugins { id 'java' }
      repositories { mavenCentral() }
      dependencies { testImplementation 'junit:junit:4.13.2' }
      """.trimIndent()
    )

    importProject()

    assertModules("project", "project.main", "project.test")
    assertSourceRoots("project.main", ExternalSystemSourceType.SOURCE, listOf(path("src/main/java")))
    assertSourceRoots("project.test", ExternalSystemSourceType.TEST, listOf(path("src/test/java")))
    assertModuleLibDep("project.test", "Gradle: junit:junit:4.13.2")
  }

  fun `test multi-module project imports both modules and the dependency between them`() {
    createProjectSubFile("settings.gradle", "rootProject.name = 'project'\ninclude 'a', 'b'\n")
    createProjectSubFile("a/build.gradle", "plugins { id 'java' }\n")
    createProjectSubFile("b/build.gradle", "plugins { id 'java' }\ndependencies { implementation project(':a') }\n")
    createProjectConfig("")

    importProject()

    assertModules(
      "project",
      "project.a", "project.a.main", "project.a.test",
      "project.b", "project.b.main", "project.b.test",
    )
    assertModuleModuleDeps("project.b.main", "project.a.main")
  }

  fun `test re-import picks up a dependency added to build_gradle`() {
    createProjectSubFile("settings.gradle", "rootProject.name = 'project'\n")
    createProjectConfig(
      """
      plugins { id 'java' }
      repositories { mavenCentral() }
      """.trimIndent()
    )
    importProject()
    assertModuleLibDeps("project.main")

    createProjectConfig(
      """
      plugins { id 'java' }
      repositories { mavenCentral() }
      dependencies { implementation 'junit:junit:4.13.2' }
      """.trimIndent()
    )
    importProject()

    assertModuleLibDep("project.main", "Gradle: junit:junit:4.13.2")
  }

  fun `test Kotlin DSL project imports`() {
    createProjectSubFile("settings.gradle.kts", "rootProject.name = \"project\"\n")
    createProjectSubFile("src/main/java/demo/Main.java", "package demo; public class Main {}\n")
    createProjectSubFile(
      "build.gradle.kts",
      """
      plugins { java }
      repositories { mavenCentral() }
      dependencies { testImplementation("junit:junit:4.13.2") }
      """.trimIndent()
    )

    importProject()

    assertModules("project", "project.main", "project.test")
    assertSourceRoots("project.main", ExternalSystemSourceType.SOURCE, listOf(path("src/main/java")))
    assertModuleLibDep("project.test", "Gradle: junit:junit:4.13.2")
  }
}
