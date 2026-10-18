# Phantom Spring Boot

`dev.phantom.spring.boot` · [English](./README.md)

Spring Boot 지원입니다. Spring Initializr로 새 프로젝트를 만들고, `application*.yml`과 `application*.properties` 설정을 돕습니다.

- **새 프로젝트:** File → New → Project → **Spring Boot**에서 [Spring Initializr](https://start.spring.io)로 프로젝트를 만들고 Gradle이나 Maven으로 가져옵니다.
  - Spring Boot 버전, 언어(Java, Kotlin, Groovy), 빌드(Gradle Kotlin/Groovy, Maven), JDK 버전, 패키징, `.properties`/`.yaml`을 고른 뒤 의존성을 고릅니다.
  - 고른 Spring Boot 버전에서 쓸 수 없는 의존성은 표시하고, 필요한 버전을 알려 줍니다.
  - 서버 주소를 바꾸면 직접 운영하는 Initializr 서버를 쓸 수 있습니다.

- **실행 구성:** `@SpringBootApplication` 클래스나 그 Kotlin `main`에서 Run, Debug, Profile을 누르면 일반 Application 대신 **Spring Boot** 구성이 만들어집니다. **Active profiles** 칸은 `-Dspring.profiles.active`로 넘어갑니다. IntelliJ IDEA와 같은 형식을 써서 `.idea/workspace.xml`에 있던 Spring Boot 구성이 그대로 열리고, Spring Boot 앱은 **Services** 창에 나옵니다.
- **자동완성:** 키와 값을 채워 줍니다.
  - 키는 라이브러리의 `spring-configuration-metadata.json`에서 읽습니다.
  - 프로젝트가 직접 만든 `@ConfigurationProperties` 클래스(Java, Kotlin)에서도 읽기 때문에, 설정 프로세서를 쓰지 않는 프로젝트에서도 키가 나옵니다.
  - 값은 메타데이터 힌트, true/false, enum 상수에서 가져옵니다.
- **키 문서 보기**와 **선언으로 이동**을 지원합니다.
- **검사:** 폐기 예정인 키를 알려 주고, 대체 키가 있으면 빠른 수정으로 바꿔 줍니다. 프로젝트의 `@ConfigurationProperties` 접두사 아래에 없는 키를 쓰면 그것도 알려 줍니다.

프로젝트가 Spring Boot에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. 다른 프로젝트에서는 New Project 항목만 보입니다.
