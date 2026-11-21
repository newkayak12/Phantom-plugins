# Pull requests

- One topic per PR. Unrelated fixes found on the way go into a separate PR.
- Over about 1000 hand-written lines: split into PRs that can be merged one after another, or say in the body why not.
- A PR made of several commits per `commits.md` is merged with "Rebase and merge" (or a merge commit), not "Squash",
  so the split survives on `main`. A PR with one commit may be squashed. The body says which.
- The body has: Why, What (one line per commit for multi-commit PRs), how it was verified (commands and results), and
  what was not verified.
- Issues close only through the close-linked-issues workflow on merge, never by hand. Before opening a PR, list the
  open issues (`gh issue list`); a PR that finishes one says `Closes #N` in its body, one that finishes part of it says
  `Part of #N` and ticks the checklist. A plain `#N` mention or a link in the title closes nothing.
- Before opening, check open PRs and other sessions' branches (`gh pr list`, `git worktree list`) so two agents do not
  do the same work.
- Merging, release tags and repository settings stay with the user unless the user asked for them.
