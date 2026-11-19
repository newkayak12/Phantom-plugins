# Agent skills

Task guides that Claude Code (`.claude/skills`, a link to this folder) and Codex (`.agents/skills`) load on demand.

Copied from IntelliJ IDEA Community (`.agents/skills` at `idea/262.10968.63`, Apache License 2.0, Copyright JetBrains
s.r.o. and contributors), through the Phantom repository. Only skills that apply to plugin code were taken; skills for
the IntelliJ monorepo build (`tests.cmd`, Bazel, `.iml` modules, YouTrack, Jewel releases) were left out.
Phantom-plugins changes are marked with "Phantom-plugins:" or listed here:

- `writing-tests`: note on this repository's test framework; Running Tests uses Gradle instead of `tests.cmd`.
- `code-style`: the IntelliJ build-scripts style bullet is removed.
- `actions`, `debugging`, `registry`: links to JetBrains' internal `docs/IntelliJ-Platform` pages are removed; those pages are not in IntelliJ Community.
