# Phantom Gradle DSL

`dev.phantom.gradle-dsl` · [English](./README.md)

화면 없이 동작하는 기술 플러그인입니다. Gradle 빌드 파일을 모델로 읽고 그 자리에서 고치는 API를 제공합니다. 의존성, 플러그인,
저장소, `ext` 속성, 버전 카탈로그(`libs.versions.toml`), Android 블록을 Groovy, Kotlin 스크립트, TOML에서 다룹니다.
Gradle-Java의 의존성 업데이트와 빌드 분석이 이 플러그인을 씁니다. Kotlin 스크립트 파서는 Kotlin 플러그인이 설치되어 있을 때만 켜집니다.

IntelliJ Community의 Gradle DSL API 플러그인(`android/gradle-dsl*`, Apache 2.0)을 바탕으로 Phantom의 id로 만든 플러그인입니다.
`org.jetbrains.idea.gradle.dsl`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과
번들 모듈이 그대로 동작합니다. Phantom은 IntelliJ 플러그인 대신 이 빌드를 번들합니다.
