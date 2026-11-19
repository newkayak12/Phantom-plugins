#!/usr/bin/env python3
# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
"""
Regenerates src/main/resources/themes/*.theme.json from the Islands themes and the token overrides below. The Islands
themes are read from a Phantom (or IntelliJ Community) checkout, by default ../Phantom-IDEA next to this repository:

    python3 -I theme/tools/generate-themes.py [<Phantom checkout>]

A child theme cannot just redefine a named color: the parent's `ui` values are resolved to colors before the child is
merged. So every `ui` key of the parent whose color chain passes through an overridden token is written out with the new
value. The `alt.` keys (Settings | Appearance | "Different tool window background") get the same values, so the theme
looks the same with that option on or off.
"""
import json
import pathlib
import xml.etree.ElementTree as ET

import sys

# phantom-idea: the script moved from the Phantom repository; the platform themes it reads stay there
ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[3] / "Phantom-IDEA"
OUT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "themes"

# Softer, rounder shapes than Islands: bigger panel corners and controls
SHAPE = {
  "Island.arc": 24,
  "Island.arc.compact": 18,
  "Button.arc": 12,
  "Component.arc": 10,
  "Component.arc.compact": 8,
  "Banner.arc": 14,
}

THEMES = {
  "PhantomDark": {
    "name": "Phantom Dark", "dark": True, "parent": "Islands Dark", "scheme": "Phantom Dark",
    "source": "platform/platform-resources/src/themes/islands/ManyIslandsDark.theme.json",
    "tokens": {
      # The app icon: panels of soft charcoal floating on a near-black frame, lit by warm amber eyes
      "layer-0-bg": "#17181B",
      "main-window-bg": "#111214",
      "main-window-bg-alt": "#111214",
      "tool-window-bg-alt": "#17181B",
      # Borders fade into the panels
      "layer-0-border": "#1E1F23",
      "editor-border": "#1E1F23",
      "editor-border-alt": "#1E1F23",
      "tool-window-border": "#1E1F23",
      "popup-border": "#2E2D30",
      "control-border": "#3A3A40",
      "tree-indent-guide-border": "#222327",
      # Selections glow amber instead of IntelliJ blue
      "selection-bg-active": "#45331D",
      "selection-bg-active-muted": "#30271C",
      "selection-bg-inactive": "#27272B",
      # The open tab is a warm pill with an amber edge
      "tab-selected-bg-active": "#2B2419",
      "tab-selected-bg-inactive": "#202024",
      "tab-selected-border-active": "#E0A250",
      "tab-selected-border-inactive": "#3A3A40",
      # Amber accent from the app icon's eyes: buttons, focus rings, selected toolbar items, completion matches
      "accent-brand-bg": "#D08C3A",
      "accent-brand-border": "#E0A250",
      "accent-brand-bg-secondary": "#3A2C1C",
      "accent-brand-border-secondary": "#6B4A26",
      "toolbar-selected-bg": "#4A3720",
      "toolbar-selected-bg-hovered": "#5A4226",
      "popup-completion-match-text": "#F0B865",
    },
    "ui": {**SHAPE, "Tree.rowHeight": 26, "Tree.rowHeight.compact": 22, "EditorTabs.underlineHeight": 2},
  },
  "PhantomLight": {
    "name": "Phantom Light", "dark": False, "parent": "Islands Light", "scheme": "Light",
    "source": "platform/platform-resources/src/themes/islands/ManyIslandsLight.theme.json",
    "tokens": {
      "main-window-bg": "#EDEEF1",
      "main-window-bg-alt": "#EDEEF1",
      "tool-window-bg-alt": "#FFFFFF",
      "layer-2-border": "#ECEDF0",
      "editor-border": "#ECEDF0",
      "editor-border-alt": "#ECEDF0",
      "tool-window-border": "#ECEDF0",
      "popup-border": "#E3E4E8",
      "tree-indent-guide-border": "#ECEDF0",
      "selection-bg-active": "#F3E2C9",
      "selection-bg-active-muted": "#F8EEDF",
      "selection-bg-inactive": "#E9EAEE",
      "tab-selected-bg-active": "#F6E9D6",
      "tab-selected-bg-inactive": "#EFF0F3",
      "tab-selected-border-active": "#B5711F",
      "tab-selected-border-inactive": "#EFF0F3",
      "accent-brand-bg": "#B5711F",
      "accent-brand-border": "#B5711F",
      "accent-brand-bg-secondary": "#FBF1E3",
      "accent-brand-border-secondary": "#E8C9A0",
      "toolbar-selected-bg": "#F6E3C8",
      "toolbar-selected-bg-hovered": "#F1D6B2",
      "popup-completion-match-text": "#A9651A",
    },
    "ui": SHAPE,
  },
}


def flatten(d: dict, prefix: str = ""):
  for key, value in d.items():
    if isinstance(value, dict):
      yield from flatten(value, prefix + key + ".")
    else:
      yield prefix + key, value


def resolve(value, colors: dict, tokens: dict):
  """The overridden color this value ends up at, or None when its chain touches no overridden token."""
  seen = set()
  while isinstance(value, str) and value in colors and value not in seen:
    if value in tokens:
      return tokens[value]
    seen.add(value)
    value = colors[value]
  return None


# Editor schemes: the Islands scheme with tools/<name>.overrides.xml merged over it. A bundled scheme cannot name
# another bundled scheme as its parent (plugin schemes may load first), so the result is a full copy on Darcula.
SCHEMES = {
  "PhantomDarkScheme": "platform/platform-resources/src/themes/islands/IslandSchemeDark.xml",
}


def merge_scheme(file_name: str, source: str) -> None:
  base = ET.parse(ROOT / source).getroot()
  overrides = ET.parse(pathlib.Path(__file__).resolve().parent / f"{file_name}.overrides.xml").getroot()
  base.set("name", overrides.get("name"))
  base.set("parent_scheme", "Darcula")
  for section in ("colors", "attributes"):
    target = base.find(section)
    by_name = {option.get("name"): option for option in target}
    for option in overrides.find(section):
      old = by_name.get(option.get("name"))
      if old is not None:
        target.remove(old)
      target.append(option)
  ET.indent(base, space="    ")
  text = ("<!-- generated by tools/generate-themes.py from " + source + " and tools/" + file_name + ".overrides.xml -->\n" +
          ET.tostring(base, encoding="unicode") + "\n")
  (OUT / f"{file_name}.xml").write_text(text, encoding="utf-8")
  print(f"{overrides.get('name')}: scheme with {len(list(overrides.find('colors'))) + len(list(overrides.find('attributes')))} overrides")


def main() -> None:
  for file_name, source in SCHEMES.items():
    merge_scheme(file_name, source)
  for file_name, spec in THEMES.items():
    parent = json.loads((ROOT / spec["source"]).read_text(encoding="utf-8"))
    colors, tokens = parent["colors"], spec["tokens"]
    ui = {}
    for key, value in flatten(parent["ui"]):
      color = resolve(value, colors, tokens)
      if color is not None:
        ui[key] = color
    for key in list(ui):
      if key.startswith("alt."):
        ui.setdefault(key[4:], ui[key])
      elif "alt." + key not in ui and any(k.startswith("alt.") and k[4:] == key for k, _ in flatten(parent["ui"])):
        ui["alt." + key] = ui[key]
    theme = {
      "name": spec["name"],
      "dark": spec["dark"],
      "author": "Phantom",
      "parentTheme": spec["parent"],
      "editorScheme": spec["scheme"],
      "colors": tokens,
      "ui": dict(sorted({**ui, **spec["ui"]}.items())),
    }
    (OUT / f"{file_name}.theme.json").write_text(json.dumps(theme, indent=2) + "\n", encoding="utf-8")
    print(f"{spec['name']}: {len(ui)} ui keys")


if __name__ == "__main__":
  main()
