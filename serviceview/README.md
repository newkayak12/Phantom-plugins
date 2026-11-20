# Services View

`dev.phantom.serviceview` · [한국어](./KOR.md)

The Services tool window: run configurations (the run dashboard), servers, databases, Docker and other services in one tree, each with its own output tab. It builds on Phantom's Navigation Bar for the bar above the tree.

Based on the IntelliJ Community Services View plugin (Apache 2.0), under Phantom's own id. `intellij.execution.serviceView.plugin` stays as an alias and the
content module names stay the IntelliJ ones, so the platform modules and plugins that depend on them keep working.
Phantom bundles this build instead of the IntelliJ plugin.
