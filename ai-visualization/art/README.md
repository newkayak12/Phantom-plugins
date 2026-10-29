# Theme art generators

Python + Pillow scripts that draw the pixel art of the Phantom AI Visualization themes. The plugin does not run them; it bundles their output.

- `pix.py`: colors, sprite canvas, 2:1 isometric box rasterizer, outlines.
- `sprites.py`: every sprite at native resolution, packed into `sprites.png` with an atlas `sprites.json` (54 frames, each with x/y/w/h and an anchor). Also writes `sprites-preview.png`.
- `scenes.py`: composes the mockups (`A-warm-wood.png`, `B-modern-bright.png`, `C-night.png`, native 480x300 at x3) from the sprites.

## Regenerate

Run from the repository root. `VENV` and `OUT` are outside the repository; `OUT` must exist.

```
export VENV=/tmp/phantom-ai-viz-gen/.venv OUT=/tmp/phantom-ai-viz-out
mkdir -p "$OUT" && python3 -m venv "$VENV" && "$VENV/bin/pip" install Pillow
cd ai-visualization/art && PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" sprites.py
```

Keep the venv outside the repository (never `.venv` here); nothing under `art/` but the scripts and this file is committed. Output is deterministic: the same scripts give a byte-identical `sprites.png`.

Environment variables:

- `PHANTOM_AIVIZ_OUT`: the output directory, which must exist. Default `/tmp/phantom-ai-viz`.
- `PHANTOM_AIVIZ_FONT`: a `.ttc` or `.ttf` with Korean glyphs, used by the preview and the mockups. Default is the macOS path `/System/Library/Fonts/AppleSDGothicNeo.ttc`; `scenes.py` also picks a face by index in that collection, so another font may need that index changed.

## Put the output into the plugin

Copy the two files, unchanged, to the theme's resource folder:

```
cp "$OUT/sprites.png" "$OUT/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/a/
```

The sprite sheet in the plugin must stay byte-identical to what `sprites.py` writes for theme A.

## Mockups

From `ai-visualization/art`, `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" scenes.py [A|B|C ...]` writes the sheet and the mockups for the given themes (all three without arguments).

## Add a theme B or C

There is one combined sheet, not one per theme. `build_sheet()` writes theme A's frames unsuffixed (`floor_wood_0`, `wall_rw`, `desk_rw`, ...), theme B's variants beside them under a `_b` suffix (`wall_rw_b`, `desk_rw_b`, `chair_rw_b`, `rug_0_b`, `nameplate_b`, `glass_*_b`, and `floor_tile_0`/`floor_tile_1`), and the people (`person<n>_<state>`) and bubbles (`bubble_<state>`) shared by all. There are no C frames. The 54 frames bundled in `themes/a/` already contain B's; only `ThemeA` is registered, and it uses just the unsuffixed ones.

1. To expose B: add a `Theme` (below) that names the `_b` frames, with its own palette. It can point `atlasImageResource`/`atlasIndexResource` at `themes/a/` (or at a copy under `themes/b/`); no sprite work is needed.
2. For C (or a B with new art): add its draw code and palette dict in `sprites.py` and pack its frames in `build_sheet()` under a new suffix (`_c`). Per-theme sheets would need `build_sheet()` to write separate files; as it stands, copying `sprites.png` and `sprites.json` into another `themes/<id>/` yields the same combined sheet.
3. Run `sprites.py`, copy both files into the theme's folder under `src/main/resources/dev/phantom/ai/visualization/themes/`, keeping `themes/a/` byte-identical to the output.
4. Add `object ThemeX : Theme` under `dev.phantom.ai.visualization.theme` (id, displayName, the two resource paths, `Palette`, frame names, window segments, decor, `nameplateSlice`, person/bubble names, as `ThemeA` does) and add it to the list in `Themes.all`. `Theme.frameNames()` is checked against the atlas on load, so a missing frame fails loudly. Renderer, layout and model do not change.
