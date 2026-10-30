# Phantom WebP

`dev.phantom.webp` · [English](./README.md)

[WebP](https://developers.google.com/speed/webp) 이미지(손실, 무손실, 알파 포함)를 PNG처럼 이미지 에디터와 썸네일로 엽니다.
디코딩은 [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys) ImageIO WebP 리더(BSD-3-Clause)가 Java로 처리합니다.
네이티브 라이브러리를 쓰지 않으므로 어느 OS에서나 똑같이 동작합니다. 읽기만 하며, IDE에서 WebP로 저장하지는 않습니다.

네이티브 libwebp를 로드하던 번들 WebP 플러그인을 대체합니다. Phantom에 아직 번들돼 있다면 그쪽은 꺼 주세요.
