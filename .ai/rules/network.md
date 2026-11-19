# Network and privacy

Taken from the Phantom repository (`AGENTS.md`, `docs/phantom/DEVELOPMENT.md` 1-2, `PRIVACY.md`). Phantom promises
that it and its plugins collect nothing and call nothing on their own.

- No automatic calls to JetBrains servers: nothing at startup, project open, on a timer or when a common screen opens.
  A ported plugin fails such a request before it leaves or does not register the feature (example:
  `repository-search/.../PackageSearchApiClientService.kt`), with a `// phantom-idea:` comment saying where the call
  went.
- A call that only happens after the user picks that exact download or service may stay; the PR says why.
- Any other service a plugin talks to is reached only on a user action, and is added to the list in Phantom's
  `PRIVACY.md` / `PRIVACY.ko.md` in the same pair of PRs.
- No usage statistics, telemetry, crash reporting or advertisements; ported plugins drop their FUS collectors
  (`porting.md`).
