# Markdown

`org.intellij.plugins.markdown` · [한국어](./KOR.md)

Markdown files: highlighting, completion, navigation, inspections, table and list editing, image drag and drop, language injection in code fences, front matter, and the JCEF or Compose preview. Shell Script, Kotlin and others build on its `intellij.markdown` module.

The IntelliJ Community Markdown plugin (`plugins/markdown`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261111.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.

The optional modules (JCEF and Compose preview, images, YAML and TOML front matter, TextMate fences, Java, XML) keep their own class loader, so each switches off with what it depends on.
