# Git Support

`dev.phantom.git` · [한국어](./KOR.md)

Git in the IDE: clone, commit (with GPG signing), push, pull, fetch, merge, rebase and interactive rebase, cherry-pick,
revert and reset, branches and the branch widget, the Git log and file history, annotations, stash and shelf, the
staging area, conflicts, worktrees, and Git settings under Version Control | Git. Phantom for GitHub, Modal Commit and other
plugins build on it.

Based on the IntelliJ Community Git plugin (`plugins/git4idea`, Apache 2.0), under Phantom's own id. `Git4Idea` stays as an
alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them keep
working. Phantom bundles this build instead of the IntelliJ plugin.

The shared, backend and local history modules load with the plugin; the frontend, terminal and coverage modules keep
their own class loader, as in the bundled plugin. The backend's services for the frontend are plain application services
(Phantom runs frontend and backend in one process), not remote APIs. No usage statistics are collected. The in-memory
rebase is derived from [git-revise](https://github.com/mystor/git-revise) (MIT, `licenses/NOTICE` in the jar).
