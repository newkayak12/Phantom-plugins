# Support for Java
`dev.phantom.java` · [한국어](./KOR.md)

Java in the IDE: the Java language (editing, completion, inspections, refactorings), JDKs and Java modules, compiling
and running Java applications, the debugger, and the base that Support for Gradle (Java), Maven, JUnit and other
Java plugins build on.

This is the Java plugin of IntelliJ IDEA Community (Apache 2.0), built by Phantom from its sources with Phantom's
changes. Phantom no longer bundles it from build 262.261126 on; it installs from this marketplace instead.
`com.intellij.java` and `com.intellij.modules.java-capable` stay as aliases, so plugins depending on them keep working.

Nothing is compiled in this folder. Phantom publishes the built plugin as a release of
[newkayak12/phantom-libs](https://github.com/newkayak12/phantom-libs) (`java-<version>.zip`, in the IDE's plugin
layout with `LICENSE.txt` and `NOTICE.txt`). `build.gradle.kts` pins that release's tag and sha256: `buildPlugin`
downloads the zip, checks the sha256 and ships it unchanged, and `test` checks its descriptor and notices. A new
version changes `version`, the tag and the sha256 together. `-PjavaLibZip=<absolute path>` uses a local zip instead of
the download.
