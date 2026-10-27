# Phantom Tab Groups

`dev.phantom.tabs` · [한국어](./KOR.md)

Editor tab groups, as in a web browser.

- **Groups:** right-click a tab → Add Tab to New Group / Add Tab to Group / Remove from Group. A group reads as one piece: a line in the group's color runs under all its tabs, they get a faint background in that color, and the group's name sits on its first tab as a chip filled with that color. New groups take blue, red, yellow… in turn.
- **Collapse, as in Chrome:** click the group's chip to collapse the group to the chip and its size (`Work 3`); click it again to open the group. Collapse Group / Expand Group in the tab menu do the same. Collapsing only hides tabs: files stay open and grouped tabs are never closed by the tab limit.
- **Group menu:** Rename Group…, Color, Ungroup and Close Group in the same tab menu.
- **Order:** a group's tabs stay together; drag tabs in and out of a group.
- Groups belong to each editor split and are kept with the project. Nothing changes in the tab bar until you make a group.

Collapsing needs tabs on top, in one scrolling row, not sorted alphabetically; otherwise groups only color and name their tabs.

Until 2026-10-26 this was bundled with Phantom (`phantom-tabs`) and drew a chip in the tab label through Phantom-only
platform changes. It is a plugin on the public platform API now, so it updates without a new Phantom build.

**0.2.1**: the group's name is a chip in its color, and a click on the chip collapses or opens the group, as in Chrome. New groups start at blue instead of grey.

**0.2.0**: a group looked like separate tabs with a name in front of one of them. Now a colored line joins its tabs and its name is in its color.

**0.1.1**, found running it in a real IDE: saved groups come back after a restart even when the project opens before any
editor event (before, about one restart in 13 showed plain tabs); a group's name and color stay on its own split instead
of also showing on the same file in another split; recoloring a group repaints its tabs at once.
