# Phantom for WebP
`dev.phantom.webp` · [한국어](./KOR.md)

[WebP](https://developers.google.com/speed/webp) images (lossy, lossless, with alpha) open in the image editor and show
as thumbnails, like PNG. Decoding is done in Java by the [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys)
ImageIO WebP reader (BSD-3-Clause), with no native library, so it works the same on every OS. Reading only: the IDE does
not save WebP.

Replaces the bundled WebP plugin, which loads the native libwebp. Turn that one off if your Phantom still bundles it.

**0.1.3**: renamed from WebP Support to Phantom for WebP, so the name starts with Phantom and names the project it supports. The plugin id and features are unchanged.

**0.1.2**: The plugin jar lists its packaged TwelveMonkeys ImageIO libraries (BSD 3-Clause, Harald Kuhr) in
`META-INF/THIRD-PARTY-NOTICES.txt` and carries the BSD text in `META-INF/licenses/`.
