# Support for Gradle (Java) 변경 이력

## 미배포

- IntelliJ IDEA Community 262.10968.63의 Gradle for Java 플러그인(`plugins/gradle/java`, `plugins/gradle-maven`)에서 갈라져 나온 첫 판. id는 `dev.phantom.gradle-java`이고 `org.jetbrains.plugins.gradle`는 별칭으로 남는다. Gradle 기능은 Phantom에 번들되던 플러그인과 같다. 사용 통계 수집기가 빠졌고, Phantom이 번들하지 않는 플러그인에 기대는 Groovy 스크립트의 ML 완성 순위 모듈(`intellij.gradle.java.groovy.ml`)과 Performance Testing 명령 모듈(`intellij.gradle.java.performance`)은 들어 있지 않다.
