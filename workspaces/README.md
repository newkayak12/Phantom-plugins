# Phantom Workspaces

`dev.phantom.workspaces` · [한국어](./KOR.md)

All open projects as tabs of one window.

- **Project tab strip:** a strip above the editor lists every open project. Click a tab to switch, middle-click or × to close, drag it left or right to put it elsewhere (in every window, and for Next Project Window too); renamed projects update their tab.
- **One window:** only the project in front has a window on screen; the others are hidden behind it, so there is one window in the Dock menu, Mission Control and the window switcher. A project you open comes in as a new tab of that window, and closing the tab in front shows the one before it. Project windows share one size, position and maximized state; a project switched to also takes full screen.
- **Detach:** right-click a tab and choose Detach to Separate Window, or drag the tab up or down out of the strip, to take that project out into a window of its own, set apart from the others (where you drop it, when dragged). It leaves the tabs of every window and is no longer hidden, moved or resized with them; its own window has no strip. Window | Attach to Project Tabs in that window puts it back as the last tab. Detached windows stay detached until attached or closed, not across restarts.
- **Side by side:** right-click another tab and choose Open Side by Side: the window in front takes the left half of its place and that project the right half, both on screen, both tabs underlined in every strip (the other one in a lighter color). Making either window wider or narrower from the edge between them resizes the other one so the two keep filling the place; moving either moves both. Switching to a third tab, Exit Side by Side in the tab menu, maximizing or full screen of either, or closing either goes back to one window. Known limit: the edge between them is two windows touching, not a divider of its own; drag the inner edge of either window.
- **Keys:** next/previous project with Window | Next Project Window. The Window menu and Open Recent on an open project switch tabs too.
- **Same tool windows in every tab:** a project switched to takes the tool window layout of the one in front: which side and order the stripe buttons have, which tool windows are open, their sizes, docked or floating. Tool windows only that project has (Gradle in a Gradle project, say) keep their place. Off in Settings | Advanced Settings | Phantom Workspaces.
- Works on every OS and does not use macOS native window tabs. Turn it off in Settings | Advanced Settings | Phantom Workspaces.

Until 2026-10-26 this was bundled with Phantom (`phantom-workspaces`); it is a plugin now so it updates without a new Phantom build.

**0.6.0**: two projects side by side. Open Side by Side in a tab's context menu splits the place of the project windows into a left half (the project in front) and a right half (that tab), and resizing one from the edge between them resizes the other. Switching to another tab or Exit Side by Side ends it.

**0.5.0**: a project tab can be detached into a window of its own, from the tab's context menu (Detach to Separate Window) or by dragging it out of the strip. The window comes out 40 px off the others (or where it was dropped) and the next tab takes its place; Window | Attach to Project Tabs puts it back as the last tab.

**0.4.1**: turning Phantom Workspaces off keeps the project you were looking at in front instead of covering it with another project's window, and turning it back on keeps the active project's window on screen instead of hiding it.

**0.4.0**: project tabs can be dragged to a new place. A line shows where the tab goes; the order is the same in every window and is the order Next/Previous Project Window goes through.

**0.3.0**: every project tab shows its tool windows in the same place. Each project keeps its tool window layout in its own workspace.xml, so switching tabs moved the Project, Terminal and other tool windows to where that project had them; now the project switched to takes the layout of the one in front.

**0.2.0**: the project windows are merged into one. Before, they were only stacked: every project still had its own
window on screen, so each showed up separately in the window switcher, Mission Control and the Dock, and a new project
opened as another window. Now only the tab in front is on screen.

**0.1.1**, found running it in a real IDE: a project window that opens goes where the other project windows are before
it is shown, instead of showing at its own saved place and then jumping; moving or resizing a project window just
opened moves the others with it, and coming back to it leaves it where it was moved; taking a project window full screen
no longer makes the windows behind it full screen too. Known limit: a project saved maximized still opens maximized and
jumps onto the stack a moment later, as the public platform API has no way to open it windowed.
