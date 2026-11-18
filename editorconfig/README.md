# EditorConfig Support

`dev.phantom.editorconfig` · [한국어](./KOR.md)

`.editorconfig` files: properties applied to the code style, indentation, encoding and trailing spaces of every supported language, and an editor for the files themselves with highlighting, completion of standard and IntelliJ-specific properties, inspections and quick fixes, navigation, rename, a preview of affected files, and export of a code style scheme to `.editorconfig`. Parsing and glob matching use [ec4j](https://github.com/ec4j/ec4j), packaged in the plugin.

Based on the IntelliJ Community EditorConfig plugin (`plugins/editorconfig`, Apache 2.0), under Phantom's own id.
`org.editorconfig.editorconfigjetbrains` stays as an alias and the content module names stay the IntelliJ ones, so
plugins and bundled modules that depend on them keep working. Phantom bundles this build instead of the IntelliJ plugin.

**0.1.1**: The plugin jar lists its packaged library (ec4j, Apache 2.0) in `META-INF/THIRD-PARTY-NOTICES.txt`.
