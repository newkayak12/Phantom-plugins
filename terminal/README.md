# Phantom Terminal

`dev.phantom.terminal` · [한국어](./KOR.md)

The integrated terminal (View | Tool Windows | Terminal): Bash, Zsh, Fish, PowerShell and cmd sessions in tabs, split panes and editor tabs, shell integration (command blocks, prompt, working directory tracking), command completion from the bundled command specs, clickable file links, and commands that open in the IDE. Shell Script, the Run Anything terminal commands and others build on it.

Based on the IntelliJ Community Terminal plugin (`plugins/terminal`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.terminal` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The backend, frontend, cloud and Shell Script modules keep their own class loader, as in the bundled plugin; the backend's
services are plain application services (Phantom runs frontend and backend in one process), not remote APIs. No usage
statistics are collected, and there is no Agent Workbench promotion.
