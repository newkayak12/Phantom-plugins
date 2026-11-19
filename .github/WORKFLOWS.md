# Repository checks

Service setup and troubleshooting: [SERVICES.md](SERVICES.md). Repository quality tests also run weekly on Monday at 03:53 UTC. Plugin releases retain test reports on failure and use the same result summaries as PR checks.

| Workflow | Checks |
|---|---|
| [PR health](workflows/pr-health.yml) | Maintains one size label and the merge-conflict label; refreshes open PRs when main changes. |
| [Inactive items](workflows/stale.yml) | Labels issues and PRs after 60 inactive days; updates remove the label. Never closes them automatically. |
| [Kotlin quality](workflows/pull-request.yml) | Spotless and detekt. |
| [Repository quality](workflows/quality.yml) | Script and JSON syntax, ShellCheck, workflow validation and quality-tool tests. |
| [Plugin tests](workflows/plugin-tests.yml) | Tests and packages changed plugins. Shared Gradle changes select all registered plugins; two run at a time. |
| [Strings analysis](workflows/analysis.yml) | Nightly and PR checks: Strings tests, result summary, ZIP build, JaCoCo, Codecov and SonarQube. |
| [PR routing](workflows/pr-routing.yml) | Assigns the author and requests other collaborators as reviewers. Drafts wait until ready. |
| [Labels](workflows/labels.yml) | Synchronizes [labels](labels.json) and labels issues and PRs. |
| [Linked issues](workflows/close-linked-issues.yml) | Closes linked issues after merge to main. Use `Closes #123` in the PR. |
| [Release](workflows/plugin-release.yml) | Tests, builds and publishes the selected plugin. |

Coverage reports measure the Strings plugin. Run `./gradlew :strings:test :strings:jacocoTestReport` with JDK 25; the HTML report is `strings/build/reports/jacoco/test/html/index.html`. See [AGENTS.md](../AGENTS.md) for local setup.
