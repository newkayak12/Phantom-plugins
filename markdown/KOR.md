# Phantom Markdown

`dev.phantom.markdown` · [English](./README.md)

Markdown 파일: 하이라이팅, 자동완성, 탐색, 인스펙션, 표와 목록 편집, 이미지 끌어다 놓기, 코드 블록 언어 주입, front matter, JCEF 미리보기. Shell Script, Kotlin 등이 `intellij.markdown` 모듈을 씁니다.

IntelliJ Community의 Markdown 플러그인(`plugins/markdown`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `org.intellij.plugins.markdown`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.

선택 모듈(JCEF 미리보기, 이미지, YAML·TOML front matter, TextMate 코드 블록, Java, XML)은 각자 클래스로더를 써서, 의존하는 플러그인이 없으면 그 모듈만 꺼집니다.
