# Phantom JSON

`dev.phantom.json` · [한국어](./KOR.md)

JSON, JSON5 and JSON Lines files: highlighting, formatting, folding, structure view, breadcrumbs, Copy JSON Pointer, and JSON Schema mappings, the schemastore.org catalog, completion, validation (with the networknt validator), documentation and quick fixes. Phantom's yaml, toml, markdown, openapi, github, grazie and mermaid plugins build on it.

Based on the IntelliJ Community JSON plugin (`json`, Apache 2.0), under Phantom's own id. `com.intellij.modules.json` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

**0.1.1**: The plugin jar lists its packaged libraries (json-schema-validator, Apache 2.0; Joni and JCodings, MIT) in
`META-INF/THIRD-PARTY-NOTICES.txt` and carries the MIT text in `META-INF/licenses/`.

**0.1.2**: The private schema-strategy helper of `JsonSchemaObjectBackedByJacksonBase` no longer shares a name with the root object's
`schemaInterpretationStrategy` getter, so the Plugin Verifier no longer reports it as an override of a final method.
