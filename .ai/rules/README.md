# Rules for coding agents

Short, binding rules for agents (Claude Code, Codex) working in this repository. `AGENTS.md` and `CLAUDE.md` load
them; `.ai/workflow.md` describes how a task runs. A rule here wins over a habit; the user's instruction wins over a
rule here.

| File | Covers |
|------|--------|
| [coding.md](coding.md) | How to write a change (Karpathy principles) |
| [commits.md](commits.md) | Size and content of one commit |
| [pull-requests.md](pull-requests.md) | What a PR holds and how it is merged |
| [porting.md](porting.md) | Moving an IntelliJ plugin into this repository |
| [changelog.md](changelog.md) | A `CHANGELOG.md` line per user-visible plugin change |
| [releases.md](releases.md) | Publishing a plugin: sources, asset checks, no replaced zips |
| [planning.md](planning.md) | Plans go into issues, PRs close them |
| [network.md](network.md) | No automatic calls, no telemetry |
| [startup.md](startup.md) | No startup cost without a reason |

Add a rule when a reviewer had to ask for the same thing twice. One topic per file, each rule one line with its reason.
