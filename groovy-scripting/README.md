# Phantom Groovy Scripting

`dev.phantom.groovy-scripting` · [한국어](./KOR.md)

The Groovy JSR223 scripting engine for the IDE Scripting Console, and the Groovy 3 library (with Apache Ivy, for Grape)
that Groovy, Groovy Live Templates and other plugins load through the `intellij.libraries.groovy` module.

Based on the IntelliJ Community Groovy Scripting plugin (`plugins/groovy-scripting`, with `libraries/groovy`,
`libraries/groovy/jsr223` and `libraries/ivy`, Apache 2.0), under Phantom's own id. `com.intellij.groovy.scripting` stays
as an alias and the library module names stay the IntelliJ ones, so plugins that depend on them keep working. Phantom
bundles this build instead of the IntelliJ plugin.
