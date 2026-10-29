# Theme art generators

Python + Pillow scripts that draw the pixel art of the Phantom AI Visualization themes. The plugin does not run them; it bundles their output.

- `pix.py`: colors, sprite canvas, 2:1 isometric box rasterizer, outlines.
- `sprites.py`: every sprite at native resolution, packed into sheets with an atlas each (frames with x/y/w/h and an anchor): theme A's `sprites.png`/`sprites.json` (54 frames) and `sprites-preview.png` in `OUT`, theme B's `sprites.png`/`sprites.json` (42 frames) in `OUT/b/`.
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

Copy each theme's two files, unchanged, to its resource folder:

```
cp "$OUT/sprites.png" "$OUT/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/a/
cp "$OUT/b/sprites.png" "$OUT/b/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/b/
```

The sheets in the plugin must stay byte-identical to what `sprites.py` writes; check with `cmp "$OUT/sprites.png" ../src/main/resources/dev/phantom/ai/visualization/themes/a/sprites.png` (and the same for `b/`).

## Mockups

From `ai-visualization/art`, `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" scenes.py [A|B|C ...]` writes the sheet and the mockups for the given themes (all three without arguments).

## Sheets per theme

- **A** (`build_sheet()`, `OUT/`): theme A's frames unsuffixed (`floor_wood_0`, `wall_rw`, `desk_rw`, ...), the people (`person<n>_<state>`) and bubbles (`bubble_<state>`), plus some B variants under a `_b` suffix kept from before B had a sheet of its own. `ThemeA` uses only the unsuffixed frames. Leave this function's frame list alone: the bundled `themes/a/sprites.png` must not change.
- **B** (`build_sheet_b()`, `OUT/b/`): self-contained. B's art under a `_b` suffix (`wall_rw_b`, `wall_lw_b`, `wall_corner_b`, `desk_rw_b`, `desk_lw_b`, `chair_seat_rw_b`, `chair_back_rw_b`, `chair_lw_b`, `rug_0_b`..`rug_2_b`, `nameplate_b`, `glass_rw_b`, `glass_lw_b`, `plant_big_b`, `bookshelf_lw_b`, `coffee_lw_b`), its floor as `floor_tile_0`/`floor_tile_1`, and the decor that looks the same in every theme (`whiteboard_*`, `clock_rw`, `poster_rw`, `lamp`), the people and the bubbles re-exported under A's names. The `B` dict in `sprites.py` holds B's colors.

## Add a theme C

1. Add its palette dict (as `B` is) and any new draw code in `sprites.py`, and a `build_sheet_c()` like `build_sheet_b()` that packs its frames with `pack(frames, f'{OUT}/c')`; call it from `__main__`.
2. Run `sprites.py`, copy both files into `src/main/resources/dev/phantom/ai/visualization/themes/c/`, and check that `themes/a/` and `themes/b/` are still byte-identical to the output.
3. Add `object ThemeC : Theme` under `dev.phantom.ai.visualization.theme` (id, displayName, the two resource paths, `Palette`, frame names, window segments, decor, `nameplateSlice`, person/bubble names, optional `partition()` frames, as `ThemeB` does) and add it to the list in `Themes.all`. `Theme.frameNames()` is checked against the atlas on load and by the tests, so a missing frame fails loudly. Renderer, layout and model do not change.
