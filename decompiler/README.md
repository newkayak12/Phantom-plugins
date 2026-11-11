# Java Bytecode Decompiler

`org.jetbrains.java.decompiler` · [한국어](./KOR.md)

Opening a `.class` file without sources shows Java source decompiled by Fernflower instead of a stub; the debugger maps its lines back to the bytecode, and View | Show Decompiled Class opens it from anywhere. Kotlin's bytecode decompiler builds on it.

The IntelliJ Community Java Bytecode Decompiler plugin (`plugins/java-decompiler`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261111.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.

The engine tests run on the IDE's runtime, which has no javac, so their round trips compile with ECJ only.
