// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.process.CommandLineArgumentProvider

/**
 * Phantom's product-info.json asks for class data sharing: on macOS builds with an AOT cache -XX:AOTCache=$APP_PACKAGE/Contents/
 * lib/phantom.aot, elsewhere -XX:SharedArchiveFile=$IDE_CACHE_DIR/<build>.jsa and -XX:+AutoCreateSharedArchive. Phantom's
 * launcher fills in the macros; the Gradle plugin passes the arguments as they are to the test and runIde JVMs, which then die
 * (a JVM with a Java agent can't dump the archive). Added last, these turn sharing off there. The JVM takes neither
 * -Xshare:off with -XX:AOTCache nor -XX:AOTMode with -XX:SharedArchiveFile, so [aotCache] says which kind the IDE passes.
 */
class NoClassDataSharing(private val aotCache: Boolean) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> =
    if (aotCache) listOf("-XX:AOTMode=off") else listOf("-XX:-AutoCreateSharedArchive", "-Xshare:off")
}
