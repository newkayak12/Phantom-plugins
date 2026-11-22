# Phantom for Markdown
`dev.phantom.markdown` · [한국어](./KOR.md)

Markdown files: highlighting, completion, navigation, inspections, table and list editing, image drag and drop, language injection in code fences, front matter, and the JCEF preview. Shell Script, Kotlin and others build on its `intellij.markdown` module.

Based on the IntelliJ Community Markdown plugin (`plugins/markdown`, Apache 2.0), under Phantom's own id. `org.intellij.plugins.markdown` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The optional modules (JCEF preview, images, YAML and TOML front matter, TextMate fences, Java, XML) keep their own class loader, so each switches off with what it depends on.
