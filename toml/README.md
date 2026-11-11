# Toml

`org.toml.lang` · [한국어](./KOR.md)

TOML files: highlighting, formatting, folding, references between keys and tables, an Expand Inline Table intention, JSON Schema completion and documentation, and proofreading of comments and strings when Natural Languages is on. Gradle version catalogs (`libs.versions.toml`) build on it.

The IntelliJ Community Toml plugin (`plugins/toml`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261027.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.
