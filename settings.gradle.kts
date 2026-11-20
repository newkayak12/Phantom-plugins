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
    // JetBrains' Package Search client and the kotlinx-document-store library it caches in, for repository-search/
    maven("https://packages.jetbrains.team/maven/p/kpm/public") {
      content {
        includeGroup("org.jetbrains.packagesearch")
        includeGroup("com.github.lamba92")
      }
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
include("http-client")
include("tabs")
include("workspaces")
include("icons")
include("ai-integrations")
include("ai-visualization")
include("strings")
include("rainbow")
include("ignore")
include("bytecode")
include("webp")
include("modal-commit")
include("toml")
include("yaml")
include("sh")
include("lombok")
include("decompiler")
include("coverage")
include("textmate")
include("markdown")
include("cpu")
include("mybatis")
include("logs")
include("querydsl")
include("testcontainers")
include("mermaid")
include("github")
include("editorconfig")
include("copyright")
include("htmltools")
include("testng")
include("java-i18n")
include("terminal")
include("git")
include("grammar")
include("properties")
include("junit")
include("repository-search")
include("json")
include("gradle-dsl")
include("groovy-scripting")
include("groovy")
include("groovy-live-templates")
include("images")
include("navbar")
include("recentfiles")
include("serviceview")

// Not plugins: Kotlin compiler plugins some plugins compile with
include(":compiler-plugins:fleet-rpc")
include("grid")
include("local-shared-index")
include("sync")
include("theme")

// Real-IDE probe (see realide-probe/): only with -PrealIde, never part of CI or the marketplace
if (providers.gradleProperty("realIde").isPresent) {
  include("realide-probe")
}
