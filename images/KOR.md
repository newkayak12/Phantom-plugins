# Phantom Images

`dev.phantom.images` · [English](./README.md)

이미지 파일(PNG, JPEG, GIF, BMP, ICO, TIFF, PSD 등 ImageIO와 Commons Imaging이 읽는 형식)을 뷰어로 엽니다: 확대, 격자,
투명 체스판, 썸네일, 배경 이미지로 지정, 외부 편집기, 그리고 채널·흑백·이진화·정규화·회전을 다루는 과학용 보기. SVG 파일은
미리보기(가능하면 JCEF)가 있는 XML이고 PNG로 변환할 수 있습니다. Phantom의 htmltools, markdown, webp 플러그인이 이것을
바탕으로 동작합니다.

IntelliJ Community의 Images 플러그인(`images`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인이며, 사용 통계는 뺐습니다.
`com.intellij.platform.images`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인이
그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
