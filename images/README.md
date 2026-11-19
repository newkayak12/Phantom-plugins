# Phantom Images

`dev.phantom.images` · [한국어](./KOR.md)

Image files (PNG, JPEG, GIF, BMP, ICO, TIFF, PSD and the other ImageIO and Commons Imaging formats) open in a viewer: zoom,
grid, transparency chessboard, thumbnails, Set Background Image, an external editor, and a scientific view for channels,
grayscale, binarize, normalize and rotate. SVG files are XML with a preview (JCEF when available) and Convert SVG to PNG.
Phantom's htmltools, markdown and webp plugins build on it.

Based on the IntelliJ Community Images plugin (`images`, Apache 2.0), under Phantom's own id, without its usage statistics.
`com.intellij.platform.images` stays as an alias and the content module names stay the IntelliJ ones, so plugins that
depend on them keep working. Phantom bundles this build instead of the IntelliJ plugin.
