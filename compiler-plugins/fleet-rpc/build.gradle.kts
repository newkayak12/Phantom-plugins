// The Fleet RPC Kotlin compiler plugin from IntelliJ Community (fleet/compiler-plugins/rpc, Apache 2.0), unchanged. The IntelliJ
// sources of a plugin that declares @Rpc interfaces (recentfiles, serviceview) need it: it generates each interface's
// RemoteApiDescriptor and rewrites remoteApiDescriptor<T>() calls to pass it. Without it those calls fail at run time with
// "Couldn't get remoteApiDescriptor ..., enable RPC plugin". Not a Phantom plugin: it goes on the Kotlin compiler plugin class
// path of the plugins that need it, as kotlinCompilerPluginClasspathMain(project(":compiler-plugins:fleet-rpc")).
plugins {
  id("org.jetbrains.kotlin.jvm")
}

kotlin {
  jvmToolchain(25)
  // Loaded into the Kotlin compile daemon, which runs on Gradle's JVM (21 here), not on the plugins' JDK 25
  compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  compilerOptions.freeCompilerArgs.addAll("-Xcontext-parameters", "-XXLanguage:+AllowEagerSupertypeAccessibilityChecks")
  // Kotlin 2.4.20 (2.4.0 builds it in IntelliJ) asks for an opt-in to read MESSAGE_COLLECTOR_KEY, as RpcComponentRegistrar does
  compilerOptions.optIn.add("org.jetbrains.kotlin.config.MessageCollectorAccess")
}

java {
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  // The Kotlin compiler the Gradle Kotlin plugin runs (buildSrc pins the plugin version), as the plugin API
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
  // gradle.properties turns off the default stdlib dependency; the compiler brings its own at run time
  compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}
