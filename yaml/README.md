# Phantom YAML

`dev.phantom.yaml` · [한국어](./KOR.md)

YAML files: highlighting, formatting, folding, structure view, breadcrumbs, anchors and aliases, inspections and intentions, and completion, validation and documentation from JSON Schema. Phantom's docker, kubernetes, openapi and migrations plugins depend on it.

Based on the IntelliJ Community YAML plugin (`plugins/yaml`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.yaml` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.
