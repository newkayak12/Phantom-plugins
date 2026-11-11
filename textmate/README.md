# Phantom TextMate Bundles

`dev.phantom.textmate` · [한국어](./KOR.md)

Highlighting, brace matching, commenting and snippets for file types no other plugin covers, from TextMate and VS Code grammars: the 64 bundled ones (`lib/bundles`) and any you add in Settings | Editor | TextMate Bundles. Markdown highlights code fences with it.

Based on the IntelliJ Community TextMate Bundles plugin (`plugins/textmate`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.textmate` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The grammars are read from this plugin's own folder wherever it is installed, not only from the IDE's `plugins/textmate-plugin`.
