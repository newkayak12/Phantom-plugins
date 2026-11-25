plugins {
  `kotlin-dsl`
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
  // kotlinx.serialization compiler plugin, for the IntelliJ sources that use @Serializable (textmate)
  implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.20")
  // Compose compiler plugin, for the IntelliJ sources with @Composable UI (workspaces' welcome screen)
  implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.20")
  implementation("org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:2.20.1")
}
