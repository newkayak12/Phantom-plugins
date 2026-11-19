# Phantom Theme

`dev.phantom.theme` · [한국어](./KOR.md)

Phantom Dark and Phantom Light, the default look of Phantom: charcoal panels on a near-black frame with the app icon's amber for selections, tabs, the caret and focus, and a warmer editor palette. Both build on the platform's Islands themes. Colors only; nothing runs.

Phantom bundles this plugin. Until 0.1.0 Phantom built it from its own repository (`plugins/phantom-theme`).

Regenerate the theme files from the Islands themes of a Phantom checkout with `python3 -I theme/tools/generate-themes.py [<Phantom checkout>]` (default `../Phantom-IDEA`). The overrides are in `tools/generate-themes.py` and `tools/PhantomDarkScheme.overrides.xml`.
