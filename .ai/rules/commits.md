# Commits

A reviewer reads one commit at a time. Each commit has to be readable on its own.

- One logical change per commit. A change that needs "and" in its subject is two commits.
- Hand-written changes: at most about 400 changed lines or 30 files per commit. Split by step (copy, adapt, wire in,
  docs) or by area, not by arbitrary file count.
- Larger commits are allowed only for content nobody reads line by line: a verbatim copy from upstream, generated
  files, test data, or moved files. The body says which, and where the content came from (repository, path, commit).
- Never mix such content with hand edits in the same commit: copy first, edit in the next commit, so the edit shows as
  a diff.
- Moves and renames get their own commit without content changes, so git keeps the history (`git log --follow`).
- Every commit compiles (`./gradlew :<folder>:compileKotlin`); the last one of a PR also passes the tests.
- Subject: one line of plain English saying what changes for the user, as in Phantom (`docs/phantom/DEVELOPMENT.md`
  4). Prefix `<folder> <version>:` for a plugin release, `<folder>:` for a change to one plugin, nothing for
  repository-wide changes. No YouTrack tickets, JetBrains prefixes or SafePush.
- Body: why, the mechanism (what was blocked or changed where), the flag that restores old behavior if any, and
  measured numbers if any. Not what the diff already shows.
- Before pushing, check: `git log --stat origin/main..HEAD`. A commit over the limit that is not copied or generated
  content is split with `git reset` / `git add -p` before the push, not after review.
