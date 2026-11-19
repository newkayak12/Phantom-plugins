# Service configuration

Use repository **Settings → Secrets and variables → Actions**. Tokens belong in secrets, never in documentation or badge URLs.

| Setting | Type | Phantom-plugins value or purpose |
|---|---|---|
| `CODECOV_TOKEN` | Secret | Upload token for `newkayak12/Phantom-plugins` in Codecov |
| `SONAR_TOKEN` | Secret | Analysis token authorized for this SonarQube project |
| `SONAR_HOST_URL` | Variable | `https://sonarcloud.io` |
| `SONAR_PROJECT_KEY` | Variable | `newkayak12_Phantom-plugins` |
| `SONAR_ORGANIZATION` | Variable | `newkayak-reservation` |

Select the existing SonarQube Cloud organization and project instead of creating another GitHub binding. Configure the project's New Code definition in **Administration → New Code**. Previous version suits versioned releases; a day window suits continuous delivery.

## Verify an upload

1. Run **Plugin analysis** from Actions on `main`.
2. Inspect the Strings test summary and JaCoCo report artifact, then the Codecov upload and SonarQube steps.
3. Confirm the same commit in both external services. Codecov's flag is `strings`; this coverage measures Strings only, not all plugins.

Fork PRs run tests without upload credentials. Missing settings fail trusted analysis runs. Dependabot PRs may lack repository secrets too; verify analysis after merge on `main`, without executing PR code in a privileged workflow.

## Diagnose failures

| Symptom | Check |
|---|---|
| Missing configuration | Exact secret/variable names above, in this repository |
| Upload rejected | Token's project access, expiration and service repository access |
| Sonar quality gate failed | New Code findings and gate conditions, separately from authentication |
| Badge unavailable | Project visibility or a service-specific badge token; never use upload credentials |
| Ruleset API returns 403 | GitHub plan support for private-repository rulesets; a JSON export alone is not enforcement |

## Releases

**Plugin release** defaults to a dry run when started manually. Tests run under Xvfb, results appear in the job summary, and available test reports are retained for 14 days even on failure. Successful builds retain their plugin ZIP. Publishing uses this repository's `GITHUB_TOKEN`; SonarQube and Codecov tokens do not authorize releases.

See [workflow checks](WORKFLOWS.md) for the maintained jobs.
