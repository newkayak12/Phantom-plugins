# Phantom Natural Languages

`dev.phantom.grazie` · [한국어](./KOR.md)

Spelling, grammar and style checks for any text in the IDE: comments, string literals, documentation, commit messages, Markdown, XML, JSON, YAML, properties and plain text. Proofreading runs locally with LanguageTool and Hunspell; English is built in, other languages are downloaded when you enable them (LanguageTool bundles from resources.jetbrains.com, Hunspell dictionaries from JetBrains' grazie-platform-public Maven repository). Grazie Cloud stays off unless `-Dphantom.jetbrains.services=true`.

Based on the IntelliJ Community Natural Languages plugin (`plugins/grazie`, Apache 2.0), under Phantom's own id. `tanvd.grazi` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.
