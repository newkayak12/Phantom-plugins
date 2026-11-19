plugins {
  id("phantom-plugin")
  jacoco
}

version = "0.1.1"

// Java 25 support; coverage is deliberately limited to this plugin initially.
jacoco {
  toolVersion = "0.8.14"
}

tasks.test {
  extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
    // IntelliJ's plugin classloader defines classes without a code source.
    isIncludeNoLocationClasses = true
    includes = listOf("dev.phantom.strings.*")
  }
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required = true
    html.required = true
  }
}
