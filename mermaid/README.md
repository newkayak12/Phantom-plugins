# Phantom for Mermaid

`com.phantom.mermaid` · [한국어](./KOR.md)

Editing support and diagram preview for [Mermaid](https://mermaid.js.org/) in `.mmd` and `.mermaid` files and in
Markdown ` ```mermaid ` fences: highlighting, completion, formatting, folding, rename and Find Usages of node ids,
inspections, and a preview next to the editor.

Built from the IntelliJ Community Mermaid plugin (`plugins/mermaid`, Apache 2.0) under its own id and packages
(`com.phantom.mermaid`), so it updates without a new Phantom build and Phantom can stop bundling the JetBrains copy.
Both register the Mermaid language and file type, so they cannot run together: with the JetBrains copy (`com.intellij.mermaid`)
enabled, this plugin is not loaded and the IDE says so. Disable Mermaid (JetBrains) in Settings → Plugins, then restart.

## Faster than the JetBrains copy

- **The preview draws the latest text only.** Edits that arrive while a diagram is drawn are merged, so 15 quick edits
  draw the diagram twice, not 15 times one after another (the preview lagged behind typing on large diagrams).
- **The preview waits for typing to pause** (150 ms instead of 20 ms) and reads the text then, instead of copying the
  whole file on every keystroke; text that did not change (undo back to it, a save) is not drawn again.
- **Markdown diagrams that did not change are not drawn again.** The Markdown preview replaces its blocks on every
  edit, and the diagrams were cached by block, so every diagram in the file was drawn again on each keystroke, and every
  replaced block stayed in memory with its SVG. They are now cached by content and theme (the 64 most recent).
- **mermaid.js (5 MB) is read once**, not for every preview, and a browser that already has it is answered before it is
  read. It is stamped with the plugin's date, not the IDE build's, so after an update the preview no longer keeps the
  old mermaid.js; the theme files are never served from the browser's cache.

## Building mermaid.js

`src/main/resources/com/phantom/mermaid/markdown/jcef/mermaid.js` is mermaid 11.15.0 with the browser side of the
plugin (`browser-build/browser/extension`, Kotlin/JS). To rebuild it after changing that code, with JDK 17:

```
cd browser-build && ./updateMermaidJs.sh
```

It also writes `mermaid.js.LICENSE.txt` next to `mermaid.js` (`browser-build/updateLicenses.py`, needs npm): the npm
packages in the bundle (mermaid, d3, cytoscape, dagre-d3-es, DOMPurify, KaTeX, lodash-es, marked, roughjs, uuid, Font
Awesome Free and others) with their licenses.

## Tests

The upstream tests (421, minus `OfficialExamplesParsingTest`, which needs the extracted documentation examples) and
`PreviewRenderingTest`, which counts the diagrams the two previews draw. The preview tests use JCEF and are skipped
where `CI` is set.

**0.1.4**: renamed from Mermaid Support to Phantom for Mermaid, so the name starts with Phantom and names the project it supports.

**0.1.3**: the plugin carries `mermaid.js.LICENSE.txt`, the licenses of the npm packages bundled in mermaid.js, which
the bundle's first line points to.
