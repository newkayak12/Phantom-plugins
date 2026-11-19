import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("phantom-plugin")
}

version = "0.1.0"

// The IntelliJ sources keep Java next to Kotlin
sourceSets {
  main { java.srcDir("src/main/kotlin") }
  test { java.srcDir("src/test/kotlin") }
}

dependencies {
  intellijPlatform {
    // The SVG copyright updater, the debugger image viewer and the JCEF SVG viewer build on these
    bundledPlugin("com.intellij.copyright")
    bundledPlugin("com.intellij.modules.jcef")
    bundledModule("intellij.platform.debugger.impl.frontend")
    bundledModule("intellij.libraries.commons.imaging")
    testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformTestBuild"))
    testFramework(TestFrameworkType.JUnit5, providers.gradleProperty("platformTestBuild"))
  }
  // SvgImageReaderSpiTest is JUnit Jupiter, ImageInfoIndexTest JUnit 4 (run by the vintage engine)
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

// The IDE the tests run in still bundles IntelliJ's Images, which claims the id this plugin keeps as an alias
tasks.prepareTestSandbox {
  disabledPlugins.add("com.intellij.platform.images")
}
