# Phantom TOML

`dev.phantom.toml` · [한국어](./KOR.md)

TOML files: highlighting, formatting, folding, references between keys and tables, an Expand Inline Table intention, JSON Schema completion and documentation, and proofreading of comments and strings when Natural Languages is on. Gradle version catalogs (`libs.versions.toml`) build on it.

Based on the IntelliJ Community TOML plugin (`plugins/toml`, Apache 2.0), under Phantom's own id. `org.toml.lang` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.
