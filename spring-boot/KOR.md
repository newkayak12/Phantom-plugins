# Phantom Spring Boot

`dev.phantom.spring.boot` · [English](./README.md)

`application*.yml`과 `application*.properties`를 위한 Spring Boot 설정 지원입니다.

- **자동완성:** 키와 값을 채워 줍니다.
  - 키는 라이브러리의 `spring-configuration-metadata.json`에서 읽습니다.
  - 프로젝트가 직접 만든 `@ConfigurationProperties` 클래스(Java, Kotlin)에서도 읽기 때문에, 설정 프로세서를 쓰지 않는 프로젝트에서도 키가 나옵니다.
  - 값은 메타데이터 힌트, true/false, enum 상수에서 가져옵니다.
- **키 문서 보기**와 **선언으로 이동**을 지원합니다.
- **검사:** 폐기 예정인 키를 알려 주고, 대체 키가 있으면 빠른 수정으로 바꿔 줍니다. 프로젝트의 `@ConfigurationProperties` 접두사 아래에 없는 키를 쓰면 그것도 알려 줍니다.

프로젝트가 Spring Boot에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. 다른 프로젝트에서는 아무 동작도 하지 않습니다.
