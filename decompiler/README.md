# Phantom Bytecode Decompiler for Java

`dev.phantom.decompiler` · [한국어](./KOR.md)

Opening a `.class` file without sources shows Java source decompiled by Fernflower instead of a stub; the debugger maps its lines back to the bytecode, and View | Show Decompiled Class opens it from anywhere. Kotlin's bytecode decompiler builds on it.

Based on the IntelliJ Community Java Bytecode Decompiler plugin (`plugins/java-decompiler`, Apache 2.0), under Phantom's own id. `org.jetbrains.java.decompiler` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The engine tests run on the IDE's runtime, which has no javac, so their round trips compile with ECJ only.

**0.1.2**: the plugin jar carries Fernflower's NOTICE (`META-INF/NOTICE-decompiler`).

**0.1.1**: the notice shown before the first class file is decompiled is a plain warning, titled "Fernflower decompiler":
decompiled code may be protected, so check the license of what you decompile.
