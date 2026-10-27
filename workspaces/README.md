# Phantom Workspaces

`dev.phantom.workspaces` · [한국어](./KOR.md)

All open projects as tabs of one window.

- **Project tab strip:** a strip above the editor lists every open project. Click a tab to switch, middle-click or × to close; renamed projects update their tab.
- **Stacked windows:** project windows share one size, position and maximized state, so switching looks like changing tabs of one window. A project switched to from the strip also takes full screen; full screen is never pushed to the windows behind.
- **Keys:** next/previous project with Window | Next Project Window.
- Works on every OS and does not use macOS native window tabs. Turn it off in Settings | Advanced Settings | Phantom Workspaces.

Until 2026-10-26 this was bundled with Phantom (`phantom-workspaces`); it is a plugin now so it updates without a new Phantom build.

**0.1.1**, found running it in a real IDE: a project window that opens goes where the other project windows are before
it is shown, instead of showing at its own saved place and then jumping; moving or resizing a project window just
opened moves the others with it, and coming back to it leaves it where it was moved; taking a project window full screen
no longer makes the windows behind it full screen too. Known limit: a project saved maximized still opens maximized and
jumps onto the stack a moment later, as the public platform API has no way to open it windowed.
