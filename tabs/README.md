# Phantom Tab Groups

`dev.phantom.tabs` · [한국어](./KOR.md)

Editor tab groups, as in a web browser.

- **Groups:** right-click a tab → Add Tab to New Group / Add Tab to Group / Remove from Group. A group reads as one piece: a line in the group's color runs under all its tabs, they get a faint background in that color, and the group's name sits on its first tab as a chip filled with that color. New groups take blue, red, yellow… in turn.
- **Collapse, as in Chrome:** click the group's chip to collapse the group to the chip and its size (`Work 3`); click it again to open the group. Collapse Group / Expand Group in the tab menu do the same. Collapsing only hides tabs: files stay open and grouped tabs are never closed by the tab limit.
- **Group menu:** Rename Group…, Color, Ungroup and Close Group in the same tab menu.
- **Order:** a group's tabs stay together. Drag the chip to move the whole group, as in Chrome; a collapsed group or a group of one tab also moves whole. Drag a tab by its body in or out of a group: dropped between two of its tabs or right after its last tab it joins (so a group of one tab can be dragged into), dropped right before its chip, or beside a collapsed group, it stays outside.
- Groups belong to each editor split and are kept with the project. Nothing changes in the tab bar until you make a group.

Collapsing needs tabs on top, in one scrolling row, not sorted alphabetically; otherwise groups only color and name their tabs.

Until 2026-10-26 this was bundled with Phantom (`phantom-tabs`) and drew a chip in the tab label through Phantom-only
platform changes. It is a plugin on the public platform API now, so it updates without a new Phantom build.

**0.2.7**: the same as 0.2.6, whose release build failed on a test that checked the tab order before a tab dropped back on the bar had finished opening again.

**0.2.6**: a tab dragged off the tab bar and dropped back on it keeps its group. Past a few pixels off the bar the platform takes the tab out (closes it) and opens it again where it is dropped; the closing took it out of its group, so a group's chip dragged a little low left its first tab outside the group, and a group of one tab was gone. Now such a drop counts as a move along the bar: the chip, a collapsed group or a group of one takes the whole group, as in Chrome. Dropped in another split or window, the tab leaves the group as before. The tab no longer shows in its place while it is being dragged off the bar.

**0.2.5**: dragging beside a collapsed group no longer breaks groups up. A tab dropped right after a collapsed group, or among the tabs it hides, went into that group (and the group opened), out of the group it was in; and the collapsed group could jump to another place as other tabs moved. Now a collapsed group takes no tab, as in Chrome, and stays where its chip is.

**0.2.4**: a tab dropped right after a group's last tab joins the group, as in Chrome. Before, a tab had to land between two tabs of a group, so a group of one tab could not be dragged into at all.

**0.2.3**: the same as 0.2.2, whose release build failed on a test that checked the tab order before a new tab had finished opening.

**0.2.2**: dragging no longer breaks groups up. Dragging the chip, a collapsed group or a group's only tab took that tab out of its group; now the whole group moves with it.

**0.2.1**: the group's name is a chip in its color, and a click on the chip collapses or opens the group, as in Chrome. New groups start at blue instead of grey.

**0.2.0**: a group looked like separate tabs with a name in front of one of them. Now a colored line joins its tabs and its name is in its color.

**0.1.1**, found running it in a real IDE: saved groups come back after a restart even when the project opens before any
editor event (before, about one restart in 13 showed plain tabs); a group's name and color stay on its own split instead
of also showing on the same file in another split; recoloring a group repaints its tabs at once.
