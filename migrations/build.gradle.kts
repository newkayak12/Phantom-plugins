plugins {
  id("phantom-plugin")
}

version = "0.3.1"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
