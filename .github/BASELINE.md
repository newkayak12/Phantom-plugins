# Repository baseline

Workflow configuration becomes active on the default branch after the PR merges. The baseline does not imply every plugin has coverage or every downstream dependency is tested.

| Area | Configuration and scope |
|---|---|
| Quality | `quality.yml`: changed-file whitespace, JSON/script syntax, ShellCheck and actionlint; quality-runner regression tests. |
| Plugin tests | `plugin-tests.yml`: changed plugin source/build modules on JDK 25, at most two simultaneously. Build script/META-INF descriptor changes also build a plugin ZIP. Strings is tested separately. Shared build changes get a Strings smoke check; downstream modules are not inferred. |
| Analysis | `analysis.yml`: Strings unit/IDE tests, plugin ZIP packaging and nonempty JaCoCo reports uploaded to SonarQube/Codecov. Coverage and Sonar analysis currently cover Strings only. |
| Releases | `plugin-release.yml`: selected plugin build/test/package and existing explicit release triggers. No publication occurs merely by editing repository settings. |
| AI | Root `AGENTS.md`, thin `CLAUDE.md`, `.ai/workflow.md`; tracked shared instructions and ignored developer state. |
| Collaboration | Author assignment, human collaborator review requests, additive labels and closing-linked-issue automation. Metadata workflows never execute PR code. |
| Contributor intake | PR template, bug/feature forms, contributor and security guidance. |
| Branch policy | `.github/rulesets/main.json`: PR required, zero approvals, admin bypass. Applying live rules is blocked by the private repository's GitHub plan (HTTP 403). |
| Repository settings | Default `main`, squash merge enabled, other merge methods disabled, merged branches deleted; Actions default token read-only, workflow PR approval disabled. |
| Documentation | English/Korean README and analysis badges. Existing measured values and upstream notices are preserved. |

Service credentials are Actions secrets. Badges follow `main`, so main-branch coverage appears after a successful main upload. See [automation details](README.md) and [branch policy](rulesets/README.md).

GitHub Actions dependencies are grouped weekly by Dependabot. Gradle setup validates wrapper JARs before plugin builds. Runtime dependency and platform upgrades are not automated by that configuration.

## Reference adaptation

| Reference workflow family | Plugin adaptation |
|---|---|
| Module tests / build | Changed plugin tests, descriptor/build-script ZIP checks and selected-plugin release builds. Strings provides the shared-configuration smoke check. |
| Publish test results | JUnit XML, HTML and measured JaCoCo reports are retained as Actions artifacts; no extra PR-write reporting token is needed. |
| SonarQube / Codecov | Strings-only source analysis and measured coverage. Extending coverage requires tests/reports for additional modules. |
| Assignee, labels, issue closing | Metadata-only author/reviewer routing, additive labels and closing-reference handling. |
| Daily tests | No scheduled full-plugin sweep; affected modules and Strings are checked on PR/main. This avoids repeatedly building unrelated upstream ports. |
| PR size/conflict | GitHub's diff size and mergeability remain visible; no extra bot or persistent size/conflict labels are configured. |
| Stale closure / TODO-to-issue | Omitted to avoid automatically closing reports or creating issues without an explicit repository policy. |
| Claude review / Slack | Omitted: no paid AI review credentials or external message integration is configured. |

Full-collection/downstream testing, all-plugin Sonar coverage and a repository-wide Kotlin style/Detekt baseline are not implied by these checks.
