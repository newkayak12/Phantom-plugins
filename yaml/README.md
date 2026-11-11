# YAML

`org.jetbrains.plugins.yaml` · [한국어](./KOR.md)

YAML files: highlighting, formatting, folding, structure view, breadcrumbs, anchors and aliases, inspections and intentions, and completion, validation and documentation from JSON Schema. Phantom's docker, kubernetes, openapi and migrations plugins depend on it.

The IntelliJ Community YAML plugin (`plugins/yaml`, Apache 2.0), built here so it updates without a new Phantom build. The plugin id
and content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep working, and its
version (`262.261027.x`) is above the bundled build: installing it replaces a copy your Phantom still bundles.
