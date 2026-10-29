# AI Visualization / AI Integration handoff (2026-10-29)

Where the AI Office work stands, for the next session.

## Shipped
- ai-integrations 0.3.2: public API `dev.phantom.ai.api` (sessions, worktrees, change listener, focus/start, `AiSession.subagents`).
  Claude agent teams: teammates run in-process with no SubagentStart hook, so `hooks/AgentTeams.kt` polls
  `<claudeDir>/teams/*/config.json` (leadSessionId + members) every 3 s and merges them into the lead session's subagents (ids `team:<agentId>`).
- ai-visualization 0.4.0: AI Office editor tab, themes A Warm Wood / B Modern Bright / C Night Shift, subagents and teammates as helpers.

## Next
1. **Quality option (user decision):** keep today's sprites as quality "Low"; add "High" with high-res sprites, default High,
   one setting next to the theme in Settings | Tools | Phantom AI Visualization.
   - Prototype generator: `/tmp/phantom-ai-hires/` (core.py, people.py, props.py, scene.py, preview.py; Pillow venv
     `/tmp/phantom-ai-viz-gen/.venv`). Output: scene.png, compare.png (vs the user's reference `/tmp/phantom-ai-viz/reference.jpg`),
     sprites-preview.png. A copy of the scripts, compare.png and the reference is in `ai-visualization/art/hires/` (prototype, not wired into the build; the scripts still write to /tmp paths).
   - Prototype sizes: standing person 27-31 x 72 (head 14x18), seated 38x52, CRT 42x33, desk 90x70, floor tile 64x32.
   - Gaps vs reference: people are ~1/6 of frame height (reference ~1/3), so High needs a closer default zoom; stiff arms,
     the raised hand reads as a fist, back-view sleep pose weak, mirrored pods mirror light and code text, no cables.
   - High needs sheets for A, B and C (C = night shading of A as today) and a renderer grid scale per quality.
2. **Office as a tab (user request "office 도 탭으로 열어줘"):** an Office button next to Board / Dashboard in the
   Phantom AI Integration tool window, shown only when the action `dev.phantom.ai.visualization.OpenAiOffice` exists
   (ActionManager lookup; AI Integration must not depend on Visualization). Confirm with the user whether they meant
   that button or the office inside the tool window's tab strip next to the agent terminals.
3. Teammate idle/busy is not read (config has no state); helpers show every member while the team config exists.

## Notes
- Another session is editing ai-integrations (HookServer, LaunchCommand, AgentTerminalService, mcp/, sidebar, board,
  dashboard, remote, tests). Commit only your own files; test a commit in a clean `git worktree` before pushing.
- Release = push tag `<dir>-<version>`; Actions builds, tests and publishes. Bump `.phantom/marketplace.json` with it.
