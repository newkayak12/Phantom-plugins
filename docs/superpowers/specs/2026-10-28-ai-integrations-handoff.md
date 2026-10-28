# ai-integrations: handoff from the icons session (2026-10-28 17:40)

The teams run E-ba610c63 (0.2.0 restructure) and a second session edited `ai-integrations/` at the same time. The second
session stopped; this lists what it left in the working tree and what the user asked for, so the run can take it over.
Backup copies of every file below: `/tmp/tgbug.HfMU/handoff/`.

## User requests (verbatim, Korean)

1. "ai integration terminal에 이미지 끌어넣기 + 클릭 인터렉션 안되네" — dragging images into the agent terminal, and click
   interaction, do not work.
2. "wt 분리하고 하는거 orca에서 벤치마킹" — Orca's new workspace = new git worktree per task (user chose: create branch +
   worktree, start the agent in it, removal included).
3. "추가로 global로 처리하자고 add repo한거" — repos added with Add repo should be global (one list for the whole IDE, every
   window sees an added repo at once). NOTE: E-ba610c63's request drops Orca-style groups + Add repo for "current
   project" scope; this request conflicts with that and needs the user's call.
4. "그냥 그 안에 들어가는 terminal을 순정 말고 tmux같은걸로" + "없으면 설치해서 하면 더 좋고" — run agents in tmux rather than
   the plain terminal; install tmux if missing.

## Left in the working tree (tested before the run's later edits)

- `terminal/TerminalDrop.kt` (new) — files dropped on an agent terminal (Finder, Project view) are typed as paths, as
  Orca's `terminal-drop-path-writer.ts`: an image as a bracketed paste `\e[200~path\e[201~` (backslash-escaped, never
  quoted), any other file shell-escaped plus a space; macOS screenshot previews (`TemporaryItems/NSIRD_*`) copied to a
  temp dir first. Installed via `DnDSupport` on the JediTerm panel. Test: `TerminalDropTextTest` (3).
- `terminal/Tmux.kt` (new) — agents run in `tmux -L phantom new-session -s phantom-<id> -c <cwd> -e K=V … '<cmd>; exec
  $SHELL -l' \; set-option mouse on \; set-option status off`; binary from /opt/homebrew/bin, /usr/local/bin, /usr/bin
  (PATH is NOT searched: Orca puts a non-tmux shim named tmux first on it); `-Dphantom.ai.tmux=` disables; without tmux
  and with Homebrew: `brew install tmux && <tmux line> || <plain cmd>`. Session killed when the tab is disposed. Test:
  `TmuxTest` (2). Verified by hand against real tmux 3.4 (env, cwd, mouse/status options, kill).
- `terminal/TerminalStarter.kt` — `LocalTerminalStarter` wires both of the above.
- `sidebar/WorktreeCreator.kt` (new) — Orca flow through git4idea: fetch origin; base = `origin/HEAD` branch else current
  branch; name sanitized as Orca `sanitizeWorktreeName`; branch `<git user.name>/<name>`; path
  `~/orca/workspaces/<repo>/<name>` (`-Dphantom.ai.workspacesDir`); `-2`, `-3` on collision;
  `git worktree add --no-track -b`; `branch.<b>.base` config; `.worktreeinclude` literal paths copied. Remove:
  `git worktree remove [--force]` then `git branch -d` (unmerged branch kept). Test: `WorktreeCreatorInIdeTest` (4,
  real origin + clone) — written, NOT yet run green (the build was interrupted by the concurrent run).
- `sidebar/NewWorktreeDialog.kt` (new) — name / from / start agent (Claude, Codex, None).
- Bundle keys `worktree.*` in `PhantomAiBundle.properties`.
- `core/AiState.kt`: `AiStateListener` + `AiState.TOPIC`, published from `setTree`/`placeCards`/`changed()`; board and
  dashboard panels subscribe (request 3).
- LOST: the sidebar wiring lived in `sidebar/ProjectGroupsPanel.kt`, which the run deleted: repo menu "New worktree...",
  worktree menu "Remove worktree..." (refused while the worktree has live sessions; git's refusal offers a forced
  remove), and the AiState.TOPIC subscription with its test `testRepoAddedFromAnotherWindowShowsHere`. They need a home
  in the new `WorktreeTreePanel`.

## Open

- "Click interaction" is not pinned down (link Cmd-click to open file:line / URL, click-to-focus, mouse reporting to the
  TUI). tmux mouse mode may cover part of it. Orca's behavior is summarized in the research below.
- Clipboard image paste (Cmd+V of an image) is not implemented; Orca saves it as a temp PNG and bracket-pastes the path.
- Nothing above was tried in a real IDE.

## Orca research (source: /tmp/orca-src/repo/src, read only)

- Drop: `renderer/src/components/terminal-pane/terminal-drop-path-writer.ts:45-70`, `shared/image-path-paste.ts`,
  `shared/terminal-bracketed-paste-text.ts`, `main/window/dragged-temp-file-copy.ts`.
- Clipboard image: `terminal-clipboard-paste.ts:40`, `main/window/clipboard-image-temp-file.ts:44-56`.
- Links: `shared/terminal-links.ts`, `terminal-link-handlers.ts:82`, `terminal-link-activation.ts` (Cmd-click opens,
  plain click shows actions, setting `terminalLinkClickBehavior`).
- Worktree: `main/ipc/worktree-logic.ts`, `main/ipc/worktree-branch-name.ts`, `main/git/worktree-add.ts:212-286`,
  `main/git/worktree-include-file.ts`, `main/git/worktree-removal.ts`, `main/git/worktree-branch-removal.ts`.
