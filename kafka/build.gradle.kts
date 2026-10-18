plugins {
  id("phantom-plugin")
}

version = "0.1.0"

dependencies {
  // the platform has its own slf4j; two copies in one class loader chain break logging
  implementation("org.apache.kafka:kafka-clients:3.9.1") {
    exclude(group = "org.slf4j")
  }
}
