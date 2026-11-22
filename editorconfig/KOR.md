# Phantom for EditorConfig
`dev.phantom.editorconfig` · [English](./README.md)

`.editorconfig` 파일: 지원하는 모든 언어의 코드 스타일, 들여쓰기, 인코딩, 줄 끝 공백에 속성을 적용하고, 파일 자체를 편집할 때는 하이라이팅, 표준 속성과 IntelliJ 전용 속성 자동완성, 인스펙션과 빠른 수정, 탐색, 이름 바꾸기, 적용되는 파일 미리보기, 코드 스타일 스킴을 `.editorconfig`로 내보내기를 지원합니다. 파싱과 glob 매칭은 플러그인에 함께 담긴 [ec4j](https://github.com/ec4j/ec4j)가 맡습니다.

IntelliJ Community의 EditorConfig 플러그인(`plugins/editorconfig`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`org.editorconfig.editorconfigjetbrains`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는
플러그인과 번들 모듈이 그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.

**0.1.2**: 이름을 바꿨습니다: EditorConfig Support → Phantom for EditorConfig. Phantom으로 시작하고 지원하는 프로젝트 이름을 뒤에 둡니다. 플러그인 id와 기능은 그대로입니다.

**0.1.1**: 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 포함된 라이브러리(ec4j, Apache 2.0)가 나옵니다.
