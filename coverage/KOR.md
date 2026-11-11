# Code Coverage for Java

`Coverage` · [English](./README.md)

Java 실행 구성에 Run with Coverage를 더합니다. IntelliJ 러너나 JaCoCo 러너로 측정해 Coverage 도구 창, 에디터와 Project 뷰의 줄과 분기 표시, HTML 리포트를 보여 주고, `.ic`, `.exec`, LCOV 리포트를 가져올 수 있습니다.

IntelliJ Community의 Code Coverage for Java 플러그인(`plugins/coverage`, Apache 2.0)을 여기서 빌드해, Phantom을 새로 빌드하지 않아도 업데이트됩니다. 플러그인 id와 콘텐츠
모듈 이름은 IntelliJ 것을 그대로 써서 이것에 의존하는 플러그인과 번들 모듈이 그대로 동작합니다. 버전(`262.261111.x`)이 번들 빌드보다 높아,
설치하면 Phantom에 아직 번들된 사본을 대신합니다.

테스트 JVM 안에서 도는 JUnit과 TestNG 리스너는 `java-coverage-rt.jar`라는 별도 jar(Java 8 바이트코드)입니다. JaCoCo와 IntelliJ 커버리지 리포트 라이브러리는 Maven에서 받습니다.
