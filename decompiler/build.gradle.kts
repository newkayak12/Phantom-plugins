import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.1"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    bundledPlugin("com.intellij.java")
    bundledModule("intellij.platform.structureView")
    testFramework(TestFrameworkType.Plugin.Java, providers.gradleProperty("platformTestBuild"))
  }
  // The Fernflower engine tests are JUnit Jupiter; the IDE tests are JUnit 4, run by the vintage engine
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("org.eclipse.jdt:ecj:3.31.0")
  testImplementation("org.codehaus.groovy:groovy-all:3.0.25")
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's Java Bytecode Decompiler, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("org.jetbrains.java.decompiler")
}
