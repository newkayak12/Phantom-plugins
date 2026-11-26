plugins {
  id("phantom-plugin")
}

version = "0.3.2"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
