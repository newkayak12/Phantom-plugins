# Support for Gradle (Java)
`dev.phantom.gradle-java` · [English](./README.md)

Support for Gradle 위에서 Gradle 프로젝트의 Java 지원을 제공합니다. Java 소스 세트, 의존성, 컴파일러 설정 가져오기, Gradle로
애플리케이션과 테스트 실행 및 디버깅, Gradle Groovy DSL(`build.gradle`의 완성, 이동, 검사), 버전 카탈로그 이동, Gradle 새 프로젝트
마법사, Maven 저장소와 의존성 연동, Gradle 테스트 실행의 커버리지, IDE가 프로젝트를 빌드할 때 Gradle 리소스를 처리하는 JPS 빌더
(`gradle-jps.jar`, IntelliJ처럼 Apache Ant 1.10.15 포함)가 들어 있습니다.

IntelliJ IDEA Community 262.10968.63의 Gradle for Java 플러그인(`plugins/gradle/java`, `plugins/gradle-maven`, Apache 2.0)에서
갈라져 나와 이 저장소에서 Phantom의 코드로 개발하며, Phantom의 id를 씁니다. `org.jetbrains.plugins.gradle`는 별칭으로 남겨 두고
콘텐츠 모듈 이름도 IntelliJ 것을 그대로 써서, 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. Gradle 기능은 IntelliJ
플러그인과 같고 사용 통계만 빠졌습니다. Gradle Groovy 스크립트의 ML 완성 순위 기능은 들어 있지 않습니다(Phantom은 ML 완성 순위를
번들하지 않습니다).
