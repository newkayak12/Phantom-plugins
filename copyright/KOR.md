# Phantom Copyright

`dev.phantom.copyright` · [English](./README.md)

저작권 문구: 범위별로 적용하는 저작권 프로필(날짜, 파일, 프로젝트 정보를 넣는 Velocity 템플릿), 언어별 주석 서식, Update Copyright와 Generate Copyright 액션, 저장할 때와 커밋 전에 갱신, Gradle 빌드(gradle-idea-ext)에서 가져오는 프로필. Shell Script, Java, Kotlin 등은 `com.intellij.copyright.updater` 확장 지점으로 자기 파일 형식을 더합니다.

IntelliJ Community의 Copyright 플러그인(`plugins/copyright`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다. `com.intellij.copyright`는 별칭으로
남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Phantom은
IntelliJ 플러그인 대신 이 빌드를 번들합니다.
