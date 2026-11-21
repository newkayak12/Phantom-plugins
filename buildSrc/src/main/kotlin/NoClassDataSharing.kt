// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.process.CommandLineArgumentProvider

/**
 * Phantom's product-info.json asks for class data sharing (-XX:SharedArchiveFile=$IDE_CACHE_DIR/<build>.jsa,
 * -XX:+AutoCreateSharedArchive). Phantom's launcher fills in $IDE_CACHE_DIR; the Gradle plugin passes both as they are to
 * the test and runIde JVMs, which then die dumping the archive (a JVM with a Java agent can't). Added last, these turn
 * sharing off there.
 */
class NoClassDataSharing : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> = listOf("-XX:-AutoCreateSharedArchive", "-Xshare:off")
}
