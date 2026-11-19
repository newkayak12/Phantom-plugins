# Contributing to Phantom-plugins

Report reproducible problems and propose changes in [GitHub Issues](https://github.com/newkayak12/Phantom-plugins/issues). Include the affected version, platform, steps, expected behavior and relevant logs with personal data removed. For security vulnerabilities, follow [SECURITY.md](SECURITY.md).

Before changing code, read [AGENTS.md](AGENTS.md) and the affected module's guidance. Plugin features belong here; platform patches and IDE bundle selection belong in the separate Phantom repository. Read the plugin README, descriptor and Gradle build file; shared conventions live in buildSrc/.

Keep the change focused, preserve compatibility and license notices, and put user-facing strings in resource bundles. Describe the behavior changed and the reason. Use JDK 25 and the checked-in Gradle wrapper. Run affected :<plugin>:test tasks and :<plugin>:buildPlugin when descriptors or packaging change; AGENTS.md explains platform selection. Documentation-only changes need link and diff checks rather than an IDE build.

Use a plain imperative English commit subject, for example `Fix plugin alias resolution`. Include the motivation and verification for substantial changes. YouTrack tickets, JetBrains commit prefixes and Patronus/SafePush are not required for this project. Open a pull request with exact checks run and any unverified behavior; do not publish releases or change versions as an incidental part of a fix.

Claude and Codex share [AGENTS.md](AGENTS.md); [CLAUDE.md](CLAUDE.md) imports it for Claude Code. [.ai/workflow.md](.ai/workflow.md) describes task routing. Personal settings and credentials belong in ignored local files.

Preserve LICENSE, NOTICE and plugin-specific upstream attribution; this contribution guide does not change their terms.
