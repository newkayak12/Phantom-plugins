# Phantom Copyright

`dev.phantom.copyright` · [한국어](./KOR.md)

Copyright notices: copyright profiles (Velocity templates with dates, file and project info) applied to scopes, per-language comment formatting, Update Copyright and Generate Copyright actions, updating on save and before commit, and profiles imported from Gradle builds (gradle-idea-ext). Shell Script, Java, Kotlin and others add their file types through its `com.intellij.copyright.updater` extension point.

Based on the IntelliJ Community Copyright plugin (`plugins/copyright`, Apache 2.0), under Phantom's own id. `com.intellij.copyright` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.
