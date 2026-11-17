# WebP Support

`dev.phantom.webp` · [한국어](./KOR.md)

[WebP](https://developers.google.com/speed/webp) images (lossy, lossless, with alpha) open in the image editor and show
as thumbnails, like PNG. Decoding is done in Java by the [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys)
ImageIO WebP reader (BSD-3-Clause), with no native library, so it works the same on every OS. Reading only: the IDE does
not save WebP.

Replaces the bundled WebP plugin, which loads the native libwebp. Turn that one off if your Phantom still bundles it.
