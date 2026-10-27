# Phantom Workspaces

`dev.phantom.workspaces` · [한국어](./KOR.md)

All open projects as tabs of one window.

- **Project tab strip:** a strip above the editor lists every open project. Click a tab to switch, middle-click or × to close; renamed projects update their tab.
- **One window:** only the project in front has a window on screen; the others are hidden behind it, so there is one window in the Dock menu, Mission Control and the window switcher. A project you open comes in as a new tab of that window, and closing the tab in front shows the one before it. Project windows share one size, position and maximized state; a project switched to also takes full screen.
- **Keys:** next/previous project with Window | Next Project Window. The Window menu and Open Recent on an open project switch tabs too.
- Works on every OS and does not use macOS native window tabs. Turn it off in Settings | Advanced Settings | Phantom Workspaces.

Until 2026-10-26 this was bundled with Phantom (`phantom-workspaces`); it is a plugin now so it updates without a new Phantom build.

**0.2.0**: the project windows are merged into one. Before, they were only stacked: every project still had its own
window on screen, so each showed up separately in the window switcher, Mission Control and the Dock, and a new project
opened as another window. Now only the tab in front is on screen.

**0.1.1**, found running it in a real IDE: a project window that opens goes where the other project windows are before
it is shown, instead of showing at its own saved place and then jumping; moving or resizing a project window just
opened moves the others with it, and coming back to it leaves it where it was moved; taking a project window full screen
no longer makes the windows behind it full screen too. Known limit: a project saved maximized still opens maximized and
jumps onto the stack a moment later, as the public platform API has no way to open it windowed.
