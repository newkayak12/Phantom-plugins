# Support for Gradle 변경 이력

## 미배포

## 0.1.1 (2026-11-26)

- 마켓플레이스에서 설치하면 "Failed to load the plugin descriptor"로 실패하던 문제를 고쳤다. Gradle tooling 모듈의 설명 파일을 플러그인 jar에도 넣는다. (#148)

## 0.1.0 (2026-11-25)

- IntelliJ IDEA Community 262.10968.63의 Gradle 플러그인(`plugins/gradle`)에서 갈라져 나온 첫 판. id는 `dev.phantom.gradle-core`이고 `com.intellij.gradle`는 별칭으로 남는다. Gradle 기능은 Phantom에 번들되던 플러그인과 같다. 사용 통계 수집기가 빠졌고, Kotlin 빌드 스크립트 완성(`intellij.gradle.completion.kotlin`)은 들어 있지 않다.
