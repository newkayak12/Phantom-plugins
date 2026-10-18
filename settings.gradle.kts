import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "phantom-plugins"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    intellijPlatform {
      defaultRepositories()
    }
  }
}

// One folder per plugin; each is listed in .phantom/marketplace.json
include("db")
include("spring")
include("spring-boot")
include("jpa")
include("reactive-streams")
include("ktor")
include("kubernetes")
include("profiler")
include("docker")
include("redis")
include("kafka")
include("validation")
include("migrations")
include("openapi")
