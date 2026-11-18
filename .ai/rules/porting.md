# Porting an IntelliJ plugin

How to move a plugin that IntelliJ Community bundles into this repository. Plugins ported this way: github, terminal,
grid, git.

- Commit 1: the upstream files copied unchanged into `<folder>/src/main/...` and `<folder>/src/test/...`. The body
  names the upstream repository, path and commit. Nothing else in this commit.
- Commit 2: the build — `<folder>/build.gradle.kts`, `settings.gradle.kts`, `plugin.xml` id `dev.phantom.<folder>` with
  the IntelliJ id kept as `<module value="..."/>` alias.
- Then one commit per adaptation: usage statistics (FUS) removed, closed or unpublished JetBrains libraries removed,
  advertisements removed, compile fixes against the Phantom platform. Each body says what was removed and why.
- Then docs: `README.md`, `KOR.md`, root README/KOR rows, `NOTICE` and license files.
- Then the marketplace entry, only when the user asked for a release.
- Every change to code taken from IntelliJ gets a `// phantom-idea:` comment (`<!-- phantom-idea: -->` in XML,
  `# phantom-idea` above lines added at the end of an upstream `.properties` bundle) saying what changed and why, the
  same marker as in the Phantom repository. Keep such patches minimal. Files that cannot hold a comment have none;
  find the full delta with `git diff <copy commit> HEAD -- <folder>`, not by searching markers.
- Code taken from IntelliJ keeps its JetBrains copyright header. Never copy decompiled or closed-source code; a jar
  without published sources is a dependency to remove, not to copy.
- The matching Phantom change (bundling from this repository, removing `plugins/<name>`) is a separate PR in the
  Phantom repository; each PR links the other.
