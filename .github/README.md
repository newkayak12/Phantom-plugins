# Repository automation

The metadata workflows use pinned `actions/github-script` and the repository's `GITHUB_TOKEN`. They never check out or execute pull request code. They run independently of plugin builds and analysis.

| Workflow | Behavior |
|---|---|
| [Repository quality](workflows/quality.yml) | Tests the quality runner; checks changed JSON, scripts and workflows with syntax parsers, ShellCheck and actionlint. |
| [Affected plugins](workflows/plugin-tests.yml) | Tests changed plugin source/build files on JDK 25, with at most two plugins at once. Builds ZIPs for changed build scripts/META-INF descriptors. Strings is tested by analysis CI. Shared build edits get the Strings smoke check, not a claim of all-plugin testing. |
| [Strings analysis](workflows/analysis.yml) | Runs Strings unit/IDE tests and ZIP packaging, checks nonempty JaCoCo reports, and uploads to Codecov/SonarQube. Coverage is Strings-only. |
| [Plugin release](workflows/plugin-release.yml) | Builds/tests a selected plugin and publishes only for its tag or an explicitly requested manual release. |
| [PR routing](workflows/pr-routing.yml) | Assigns the PR author; requests eligible human collaborators with repository read access. Excludes the author, bots, existing requests and people who already reviewed. Drafts receive reviewers when marked ready. |
| [Labels](workflows/labels.yml) | Adds labels by issue/PR title prefix and PR file paths. Loads [labels.json](labels.json) from the default branch. Handles renamed files and preserves existing labels. |
| [Close linked issues](workflows/close-linked-issues.yml) | After a PR merges into the default branch, closes open issues from this repository that GitHub identifies as closing references. Plain mentions and cross-repository issues are excluded. |

Labels synchronize automatically when their configuration or workflow changes on `main`. To bootstrap or refresh them, run **Phantom plugins labels** from Actions → Run workflow. Sync creates missing labels and updates configured colors/descriptions; it never deletes unrelated labels. Title prefixes such as `bug:`, `fix:`, `feat:` and `docs:` are optional and case-insensitive. File rules label plugin `src/` trees, shared Gradle build code, tools, documentation and automation. Labeling is additive, so labels can remain after later title or file changes; remove those manually when appropriate.

Reviewer requests depend on eligible collaborators being available to the workflow token. Metadata automation needs no external service credentials. Keep third-party analysis credentials in GitHub Actions secrets used by the analysis workflow.

GitHub Actions dependencies are checked weekly by Dependabot and grouped into one maintenance PR. Gradle setup validates checked-in wrapper JARs before executing plugin builds. Dependency updates to plugin libraries and platform versions remain separate from this automation.

## Verification boundaries

| Area | Current automatic checks | Remaining scope |
|---|---|---|
| Repository tooling | Changed script/JSON/workflow parsers, ShellCheck errors, actionlint and quality-runner tests | These do not type-check Kotlin or Java. |
| Plugin behavior | Tests for changed module source/build files; Strings unit and IDE tests on every analysis run | Shared build edits get a Strings configuration smoke check. Downstream modules and the full plugin collection are not tested automatically. |
| Packaging | Changed module build scripts/META-INF descriptors build a plugin ZIP; releases test and build the selected plugin | Ordinary source edits run tests; whole-collection packaging remains separate. |
| Source analysis | SonarQube analyzes the Strings plugin | Other plugins, including upstream ports, have no repository-wide Detekt/style or Sonar coverage. |
| Coverage | JaCoCo XML and Codecov measure Strings | The `strings` flag and badges must not be read as whole-repository coverage. |
| Merge policy | Reviewable ruleset export, PR routing and templates | Rulesets/required status checks need supported GitHub settings and activation; an Actions result alone does not enforce merging. |

This setup adapts the reference repository's test/build/report separation. It does not copy its Slack notifications, paid AI review, automatic stale closure, or scheduled full-module builds. Extending product coverage means adding a tested module and its real JaCoCo report to analysis, not changing badges or treating skipped tests as coverage.

Claude and Codex share [AGENTS.md](../AGENTS.md); [CLAUDE.md](../CLAUDE.md) imports it for Claude. See the [AI workflow](../.ai/workflow.md). For local repository checks run `node --test tools/phantom-quality.test.mjs` and `node tools/phantom-quality.mjs --lint --base origin/main`; the lint option requires ShellCheck and actionlint on PATH, and script parsers require Bash/Zsh, Node and Python 3 as applicable.
