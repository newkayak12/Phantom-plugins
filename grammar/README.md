# Phantom Grammar

`dev.phantom.grammar` · [한국어](./KOR.md)

Grammar, style and spelling checks for any text in the IDE: comments, string literals, documentation, commit messages,
Markdown, XML, JSON, YAML, properties and plain text. Grammar and style are checked by
[LanguageTool](https://languagetool.org/) (LGPL 2.1) inside the IDE; English is built in, other languages are downloaded
when you enable them (LanguageTool bundles from resources.jetbrains.com). Spelling uses LanguageTool's English
dictionaries, the IDE's dictionaries, the LanguageTool spellers of other enabled languages and any custom Hunspell
dictionaries you add. No text leaves the IDE.

Built only on open-source code: the IDE layer of the IntelliJ Community Natural Languages plugin (`plugins/grazie`,
Apache 2.0: text extraction, inspections, quick fixes, settings and the `com.intellij.grazie.*` extension points) and
LanguageTool. JetBrains' closed-source Grazie libraries (`ai.grazie.*`) are not used; what the IDE layer needed from them
(languages, language detection, sentence splitting, code detection, the spelling engine) is code of this plugin, in
`com.intellij.grazie.nlp` and `com.intellij.grazie.spellcheck`. Compared with the IntelliJ plugin (and Phantom Natural
Languages, `dev.phantom.grazie`, which it replaces), there are no Grazie rule engine checks or writing style profiles,
no automatic fixes, no Hunspell dictionaries downloaded for German, Russian and Ukrainian, and no report to JetBrains'
YouTrack.

`tanvd.grazi` and `dev.phantom.grazie` stay as aliases and the content module names stay the IntelliJ ones, so plugins
and bundled modules that depend on them (Kotlin, Python, TOML, Maven, YAML, Markdown, ...) keep working.
