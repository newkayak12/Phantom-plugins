# TextMate Bundles

`org.jetbrains.plugins.textmate` · [한국어](./KOR.md)

Highlighting, brace matching, commenting and snippets for file types no other plugin covers, from TextMate and VS Code grammars: the 64 bundled ones (`lib/bundles`) and any you add in Settings | Editor | TextMate Bundles. Markdown highlights code fences with it.

The IntelliJ Community TextMate Bundles plugin (`plugins/textmate`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261111.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.

The grammars are read from this plugin's own folder wherever it is installed, not only from the IDE's `plugins/textmate-plugin`.
