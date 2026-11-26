plugins {
  id("phantom-plugin")
}

version = "0.6.3"

dependencies {
  intellijPlatform {
    bundledPlugin("org.jetbrains.plugins.yaml")
  }
}
