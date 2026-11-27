# Phantom Workspaces

`dev.phantom.workspaces` · [한국어](./KOR.md)

All open projects as tabs of one window, listed by repository in the Projects and Files view, and Home in place of the welcome window.

- **Projects and Files:** a view of the Project tool window (pick it in the view list at the top of the tool window; Project stays the default) lists the open projects and the recent ones. A git repository's main checkout comes first, with every worktree of it indented under it: also worktrees no project was opened in, as another tool created them, read from `.git/worktrees` without running git. A project opened in a subfolder of a checkout belongs to that checkout; a repository in the home folder itself does not claim the projects under it. Each line shows the name, the branch (a short commit hash when detached) and the location. Open projects are bold and have a ×; the current one is selected. Click an open project to switch to it, any other to open it as a tab of the window; × or a middle click closes that project. The search field above finds projects by name, location or branch. The list follows projects opening, closing and switching branch (a branch switched in a terminal at the latest when a project window is activated).
- **Home:** with no project open, Home opens in a project frame instead of the modal welcome window: Projects and Files on the left with the welcome screen's Open and New… buttons above the list, and a Home tab in the editor area (Open, New Project, Clone Repository, Settings, theme and keymap). A project opened from Home takes Home's window; closing the last project brings Home back in that window. Home lives in the IDE's config directory and is not shown in Recent Projects.
- **One window:** only the project in front has a window on screen; the others are hidden behind it, so there is one window in the Dock menu, Mission Control and the window switcher. A project you open comes in as a new tab of that window, and closing the one in front shows the one before it. Project windows share one size, position and maximized state; a project switched to also takes full screen.
- **Detach:** right-click an open project in Projects and Files and choose Detach to Separate Window to take it out into a window of its own, set apart from the others. It is no longer hidden, moved or resized with them. Window | Attach to Project Tabs in that window puts it back as the last tab. Detached windows stay detached until attached or closed, not across restarts.
- **Side by side:** right-click another open project and choose Open Side by Side: the window in front takes the left half of its place and that project the right half, both on screen. Making either window wider or narrower from the edge between them resizes the other one so the two keep filling the place; moving either moves both. Switching to a third project, Exit Side by Side in the same menu, maximizing or full screen of either, or closing either goes back to one window. Known limit: the edge between them is two windows touching, not a divider of its own; drag the inner edge of either window.
- **Keys:** next/previous project with Window | Next Project Window. The Window menu and Open Recent on an open project switch tabs too.
- **Same tool windows in every tab:** a project switched to takes the tool window layout of the one in front: which side and order the stripe buttons have, which tool windows are open, their sizes, docked or floating. Tool windows only that project has (Gradle in a Gradle project, say) keep their place. Off in Settings | Advanced Settings | Phantom Workspaces.
- Works on every OS and does not use macOS native window tabs. Turn it off in Settings | Advanced Settings | Phantom Workspaces: the projects open as separate windows again and the modal welcome window comes back. So does uninstalling the plugin.

Projects and Files and Home are IntelliJ Community's non-modal welcome screen (`platform/non-modal-welcome-screen`, Apache 2.0), built into this plugin under its IntelliJ module names; Phantom does not bundle it. Changed from it: no usage statistics, no startup activity, no remote development slot, Projects and Files in every project frame and showing this plugin's list.

Until 2026-10-26 this was bundled with Phantom (`phantom-workspaces`); it is a plugin now so it updates without a new Phantom build.

**0.6.1**: tabs that do not fit get narrower (titles cut with …), and past a minimum width the strip scrolls with the wheel or a trackpad, the current tab kept in view; before, the last tabs ran off the window and could not be reached. Projects that share a name (the worktrees of one repository) are titled by the shortest end of their location that tells them apart, the whole location in the tooltip.

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
