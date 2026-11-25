# Support for Gradle
`dev.phantom.gradle-core` · [English](./README.md)

IDE 안의 Gradle입니다. Gradle 프로젝트를 만들고 열고, 프로젝트 구조를 Gradle 모델과 동기화하고, Gradle 도구 창, Gradle 실행
구성과 태스크 실행, Gradle 데몬 관리, Gradle 파일 편집 도움(빌드 스크립트와 버전 카탈로그의 의존성 완성, `gradle.properties`)을
제공합니다. 언어 지원은 이것을 바탕으로 합니다. Support for Gradle (Java)가 Java 프로젝트, Gradle Groovy DSL, JPS 빌드를 더합니다.

IntelliJ IDEA Community 262.10968.63의 Gradle 플러그인(`plugins/gradle`, Apache 2.0)에서 갈라져 나와 이 저장소에서 Phantom의
코드로 개발하며, Phantom의 id를 씁니다. `com.intellij.gradle`는 별칭으로 남겨 두고 콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서,
이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Gradle 기능은 IntelliJ 플러그인과 같고, Phantom의 변경만 더해져 있습니다.
사용 통계를 보내지 않고, JetBrains에서 Gradle/JVM 호환 목록을 자동으로 받지 않으며, 프로젝트 SDK가 프로젝트의 Gradle이 지원하지
않는 JDK면 지원하는 JDK로 Gradle을 실행하고, 툴링 프록시의 Groovy는 Groovy Scripting에서 가져옵니다. Kotlin 빌드 스크립트
완성은 들어 있지 않습니다. Phantom이 번들하지 않는 Kotlin 플러그인과 함께 갈 기능입니다.

Gradle API와 Tooling API(`gradle-api-9.6.0.jar`), IDE가 Gradle 데몬에 넘기는 툴링 확장 jar(`gradle-tooling-extension-api.jar`,
`gradle-tooling-extension-impl.jar`, `gradle-tooling-proxy.jar`, Java 8용으로 컴파일)를 IntelliJ와 같은 배치로 담고 있습니다.
