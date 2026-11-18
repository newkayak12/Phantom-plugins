# AI-assisted plugin work

1. Identify the plugin and source owner. Read `AGENTS.md`, the plugin README, Gradle file, descriptor, and relevant tests. For platform or bundle changes, consult the separate Phantom repository rather than assuming a same-named upstream source is shipped.
2. State the intended behavior and choose the smallest affected module. Preserve public plugin IDs, aliases, content modules, and legal notices. Ask only for missing decisions that block the work.
3. Implement and run the affected Gradle tests with JDK 25. Build the plugin zip when packaging changes. Use `-PplatformFromRepository=true` if no compatible local Phantom is available; record that Phantom-specific runtime behavior remains unverified.
4. Review the diff for unrelated edits, resource strings, threading, startup cost, and compatibility. Summarize changes, exact verification, and remaining limitations. Commit, publish, or change release metadata only within the user's requested scope.

## Route skills by task

The `newkayak12/claude-skills` collection is an optional source of task-specific workflows, not a required bulk installation. Use an available skill when the user names it or its instructions materially help: design before an open-ended implementation, debugging for a reproducible failure, verification before a completion claim, and writing review for public prose. Read the selected skill and apply only the relevant workflow; repository rules and user instructions take precedence.

Delegate independent tasks only when the user or applicable instructions request parallel agents. Keep ownership clear so agents do not edit the same files. Do not install skills, plugins, or local hooks as an incidental part of a code fix.

Keep credentials, machine paths, session transcripts, and generated agent state out of shared instructions. `.ai/local.md` and `.claude/settings.local.json` are local preferences; `.ai/workflow.md`, `AGENTS.md`, `CLAUDE.md`, and shared skill definitions are maintained project files.
