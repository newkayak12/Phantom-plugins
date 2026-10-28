# AI Integrations

`dev.phantom.ai` · [한국어](./KOR.md)

Claude Code and Codex next to your projects. Needs Phantom Workspaces (`dev.phantom.workspaces`).

- **Tool window:** the AI Integrations tool window (right side) has a Projects tab (the project-group sidebar), a Board tab and every agent terminal as its own tab. In the sidebar, group repositories into nested groups (new, rename, nest, reorder, collapse, drag and drop; a group cannot be dropped into its own subgroup). Each repository lists its worktrees and each worktree its agent sessions, with the open pull request title when the GitHub plugin has an account for the remote.
- **Sessions:** New Claude Session, New Codex Session and Resume Session start `claude` or `codex` in the worktree, in a terminal tab of that tool window. A session shows working, waiting for you or done, with an unread mark.
- **Board:** the Board tab shows every worktree as a card in columns (Todo, In progress, In review, Done); drag a card to move it. A card carries its branch, session status dots and prompt.
- **Dashboard:** Open Agent Dashboard (Find Action) opens a separate window: sessions by Needs attention, Working and Done, with the last prompt and answer, subagents, search and agent/project filters. Click a session to jump to its terminal.
- **Status bar:** the usage widget shows Claude `5h` and `wk` (and Fable) and Codex `5h` and `wk`, with reset times in its tooltip.
- **Accounts:** Settings | Tools | AI Integrations lists the System default and any accounts you add, per agent. Adding one makes its own config dir (`CLAUDE_CONFIG_DIR` or `CODEX_HOME`), installs the hooks there and opens the CLI's login in a terminal. The one marked active is used by new terminals. Phantom stores no tokens; the CLI owns the login.

## Read before using

- **Permission checks are always skipped.** There is no setting. Every terminal runs `claude --dangerously-skip-permissions` or `codex --dangerously-bypass-approvals-and-sandbox` (resume: `claude --dangerously-skip-permissions --resume <id>`, `codex resume --dangerously-bypass-approvals-and-sandbox <id>`). The agent can run commands and edit files in the worktree without asking.
- **Hooks are written to your agent config at IDE start,** for the System default and every account dir: Claude Code's `~/.claude/settings.json` (hooks for its events) and Codex's `<CODEX_HOME>/hooks.json` (default `~/.codex/hooks.json`). Your own hooks and other keys stay; a symlinked file stays a symlink and keeps its permissions; a file that is not valid JSON is left alone and a warning is shown. The hooks post events to a local server (a random port, a token) and carry no data off the machine. Codex asks you to review and trust new hooks itself; nothing is written to its `config.toml`.
- **The Claude `statusLine` is taken over.** `statusLine` in `settings.json` is replaced by the plugin's script (it feeds the usage widget); a previous one is not backed up, so Claude started outside Phantom shows it too.
- **Usage reads credentials.** On macOS the Claude OAuth token is read from the keychain through `/usr/bin/security`, then `.credentials.json` in the config dir; Codex usage comes from `codex app-server` and its `auth.json`. They go only to the vendors' own usage endpoints.

Failures starting a terminal or installing hooks are logged and shown as a notification. With `-Dphantom.ai.claudeDir` / `-Dphantom.ai.codexDir` set (the real-IDE probe does), the System default is that dir everywhere: hook install, `CLAUDE_CONFIG_DIR` / `CODEX_HOME` of launched agents, credentials (`.credentials.json` there; the keychain is read with that dir's suffixed service name), Codex usage and session lookup. Without them the System default sets no environment variable and uses `~/.claude` / `~/.codex`. The probe also sets `-Dphantom.ai.agentBinDir` (stub `claude`/`codex` that only log their calls) and `-Dphantom.ai.keychain=false` (the keychain and `/usr/bin/security` are never read).
