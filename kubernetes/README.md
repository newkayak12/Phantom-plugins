# Phantom Kubernetes

`dev.phantom.kubernetes` · [한국어](./KOR.md)

Helm chart support. It works inside chart templates (`<chart>/templates/…` next to `<chart>/Chart.yaml`) and nowhere else.

- **Completion:** `.Values.` and `$.Values.` paths from the chart's `values.yaml`, one level at a time with the default value shown; `.Release.`, `.Chart.` and `.Capabilities.` fields; template names in `include "…"` and `template "…"`. Typing `.` opens the list.
- **Navigation:** Ctrl+B on a `.Values` path goes to its key in `values.yaml`, and on an `include` name to its `define`.
- **Missing values (weak warning):** a `.Values` path that `values.yaml` doesn't define, such as a typo in `.Values.imgae.tag`. Paths under `.Values.global`, and below a key whose default is `{}` or null, are not reported.
- `_helpers.tpl` files open as text so their `define` blocks are found.

Phantom suggests this plugin when a project contains a `Chart.yaml`.

For Kubernetes manifests (schema completion, cluster browsing), install **Kubernetes by Red Hat** from the Marketplace; it works in Phantom as is.

Not yet: a Go template language (highlighting of `{{ }}` actions), `values.schema.json`, rendering a chart.
