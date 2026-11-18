# Planning in issues

Where the plan for a new plugin or a larger change lives. Taken from the Phantom repository's rule of the same name.

- The plan goes into a GitHub issue in this repository, not into a file under `docs/`: an issue can be discussed,
  linked and closed, a plan file goes stale once the work ships.
- The issue holds why, the goal, non-goals, the chosen approach (with the others considered) and the task list as
  checkboxes. Design detail the code will not show (measured numbers, rejected approaches) stays in the issue too.
- Every PR implementing the plan says `Closes #<issue>` in its body (or `Part of #<issue>` until the last one), so the
  issue closes automatically on merge (`.github/workflows/close-linked-issues.yml`).
- Nothing private goes into an issue: no credentials or tokens, no personal or company names and data, no local
  machine paths, no session transcripts, no content of private repositories or closed JetBrains code. Write the plan so
  it would still be fine if the repository were public.
- Notes that are only useful to one agent session (scratch plans, ledgers) stay in ignored local files and are not
  committed.
