# Phantom Terminal

`dev.phantom.terminal` · [한국어](./KOR.md)

The integrated terminal (View | Tool Windows | Terminal): Bash, Zsh, Fish, PowerShell and cmd sessions in tabs, split panes and editor tabs, shell integration (command blocks, prompt, working directory tracking), command completion from bundled command specs (generated from the Fig specs), clickable file links, and commands that open in the IDE. Shell Script, the Run Anything terminal commands and others build on it.

Based on the IntelliJ Community Terminal plugin (`plugins/terminal`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.terminal` stays
as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.

The backend, frontend, cloud and Shell Script modules keep their own class loader, as in the bundled plugin; the backend's
services are plain application services (Phantom runs frontend and backend in one process), not remote APIs. No usage
statistics are collected, and there is no Agent Workbench promotion.

The Java support (JDK on the terminal PATH, `java` and `javac` completion, the Java section of the terminal settings) is
here too, as the optional `intellij.java.terminal.*` modules moved from the IDE core, so the core compiles without the
Terminal plugin. They switch off without the Java plugin. Version 0.3.0 needs Phantom 262.261123 or later: earlier builds
still have them in the core.

Command completion comes from the [Fig autocomplete specs](https://github.com/withfig/autocomplete) (MIT, `terminal/LICENSE`):
`tools/fig-specs` converts a pinned version of them into the JSON in `src/main/resources/completionSpecs` (720 commands,
git, docker, npm, kubectl, aws, az and gcloud among them), which is committed, so the build needs no Node. JSON holds only
what Fig describes statically: subcommands, options, arguments, fixed values, and files or folders. Fig's generators that
run a command for their suggestions (git branches, docker containers, npm scripts) are left out; the plugin's own
generators (`cd`, `make` targets) and those of other plugins still apply.

**0.2.0**: the command specs are generated from the open Fig specs instead of coming from JetBrains' unlicensed
completion libraries (`completion-spec`, `completion-db-with-extensions`), whose classes are replaced by the plugin's own
(`dev.phantom.terminal.completion.spec`). The machine-learned ordering of Shell Script completion (`completion-ranking-sh`)
is gone too; suggestions come in the default order. Hidden subcommands and options of a spec are no longer suggested,
only recognized.
