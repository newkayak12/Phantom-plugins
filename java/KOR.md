# Support for Java
`dev.phantom.java` · [English](./README.md)

IDE 안의 Java입니다. Java 언어(편집, 완성, 검사, 리팩터링), JDK와 Java 모듈, Java 애플리케이션 컴파일과 실행, 디버거를
제공하고, Support for Gradle (Java), Maven, JUnit 같은 Java 플러그인이 이것을 바탕으로 동작합니다.

IntelliJ IDEA Community의 Java 플러그인(Apache 2.0)을 Phantom이 소스에서 Phantom의 변경을 더해 빌드한 것입니다. Phantom은
262.261126 빌드부터 이것을 번들하지 않고, 이 마켓플레이스에서 설치합니다. `com.intellij.java`와
`com.intellij.modules.java-capable`은 별칭으로 남아 있어서, 이것에 의존하는 플러그인이 그대로 동작합니다.

이 폴더에서는 아무것도 컴파일하지 않습니다. Phantom이 빌드한 플러그인을
[newkayak12/phantom-libs](https://github.com/newkayak12/phantom-libs)의 릴리스(`java-<version>.zip`, `LICENSE.txt`와
`NOTICE.txt`가 든 IDE 플러그인 배치)로 올리고, `build.gradle.kts`가 그 릴리스의 태그와 sha256을 고정합니다. `buildPlugin`은
zip을 내려받아 sha256을 확인한 뒤 그대로 내보내고, `test`는 디스크립터와 고지 파일을 확인합니다. 새 버전은 `version`, 태그,
sha256을 함께 바꿉니다. `-PjavaLibZip=<절대 경로>`를 주면 내려받는 대신 로컬 zip을 씁니다.
