# phantom-plugins

[Phantom IDEA](https://github.com/newkayak12/Phantom-IDEA)용 플러그인 모음입니다.
IDE에 기본으로 넣지 않고 따로 설치하게 해서, 프로젝트가 실제로 쓰는 기술만 비용을 냅니다. [English](./README.md)

## 설치

Phantom에는 이 저장소가 기본으로 등록돼 있습니다. **Settings | Plugins | Marketplace**에서 플러그인 이름으로 찾아 설치하면 됩니다.
프로젝트에서 플러그인의 `detect`에 적힌 라이브러리나 `detectFiles`에 적힌 파일이 보이면 Phantom이 먼저 설치를 제안합니다.

같은 형식을 따르는 다른 저장소도 추가할 수 있습니다. **Settings | Plugins | ⚙ | Manage Plugin Repositories**에서
`owner/repo`, `owner/repo@branch`, github.com 주소 중 하나를 입력하면 됩니다.

**비공개 저장소**를 읽으려면 그 저장소 내용을 읽을 권한이 있는 GitHub 토큰이 필요합니다.
Phantom은 아래 순서로 토큰을 찾고, 등록된 저장소의 `api.github.com` 요청에만 토큰을 보냅니다.

1. **Find Action → Set GitHub Token for Plugin Repositories**로 저장한 토큰 (IDE 비밀번호 저장소에 보관)
2. 환경 변수 `GH_TOKEN` 또는 `GITHUB_TOKEN`
3. GitHub CLI에 로그인돼 있으면 `gh auth token`

공개 저장소는 토큰이 없어도 됩니다.

## 플러그인

| 플러그인 | 폴더 | 설명 |
|----------|------|------|
| Phantom Database Console | [db](./db/KOR.md) · [English](./db/README.md) | 드라이버를 추가할 수 있는 JDBC 콘솔(MySQL, PostgreSQL, H2, AWS, Oracle 등). 에디터에서 SQL 실행, 결과 그리드, 스키마 트리 |
| Phantom Spring | [spring](./spring/KOR.md) · [English](./spring/README.md) | 빈 주입 이동, 자동 주입 검사, Spring MVC 엔드포인트, Security 경로·표현식, Kafka·RabbitMQ·JMS 이동 (Java, Kotlin) |
| Phantom Spring Boot | [spring-boot](./spring-boot/KOR.md) · [English](./spring-boot/README.md) | Spring Initializr로 새 Spring Boot 프로젝트 만들기, `application.yml` / `.properties` 키·값 자동완성과 문서 |
| Phantom JPA | [jpa](./jpa/KOR.md) · [English](./jpa/README.md) | `@Query` 안의 JPQL, 파생 쿼리·`@Modifying` 검사, 엔티티 매핑 검사, 리포지토리·엔티티 이동 |
| Phantom Reactive Streams | [reactive-streams](./reactive-streams/KOR.md) · [English](./reactive-streams/README.md) | 구독하지 않은 `Mono`/`Flux`, 연산자 안의 블로킹 호출 (Reactor, RxJava) |
| Phantom Ktor | [ktor](./ktor/KOR.md) · [English](./ktor/README.md) | Ktor 라우트를 Endpoints 툴 윈도우에 표시 (`Route` 확장 함수 안의 라우트 포함) |
| Phantom Kubernetes | [kubernetes](./kubernetes/KOR.md) · [English](./kubernetes/README.md) | Helm 템플릿: `.Values` 자동완성, 이동, 없는 값 경고 |
| Phantom Profiler | [profiler](./profiler/KOR.md) · [English](./profiler/README.md) | JFR 프로파일링: Profile 실행, 실행 중인 JVM 녹화, 플레임 그래프·콜 트리 보기 |
| Phantom Bean Validation | [validation](./validation/KOR.md) · [English](./validation/README.md) | 모순된 범위, 맞지 않는 타입의 제약, Kotlin `@field:` 대상, 빠진 `@Valid` / `@Validated` |
| Phantom Migrations | [migrations](./migrations/KOR.md) · [English](./migrations/README.md) | Flyway 파일 이름과 중복 버전, Liquibase 중복 changeset과 없는 include |
| Phantom OpenAPI | [openapi](./openapi/KOR.md) · [English](./openapi/README.md) | OpenAPI/Swagger 스펙의 `$ref` 이동·자동완성·오류, 스펙 ↔ 핸들러 거터 |
| Phantom HTTP Client | [http-client](./http-client/KOR.md) · [English](./http-client/README.md) | `.http` / `.rest` 요청을 에디터에서 실행: 환경 변수, `import` / `run`, 검사, 응답 창 |
| Phantom Tab Groups | [tabs](./tabs/KOR.md) · [English](./tabs/README.md) | 브라우저처럼 에디터 탭 그룹: 이름, 색, 첫 탭으로 접기, 끌어서 넣고 빼기, 분할마다 |
| Phantom Workspaces | [workspaces](./workspaces/KOR.md) · [English](./workspaces/README.md) | 열린 프로젝트를 한 창의 탭처럼: 에디터 위 프로젝트 탭 줄, 같은 크기·위치로 겹친 프로젝트 창 |

## 저장소 형식

Phantom은 저장소의 기본 브랜치(`@` 뒤에 적으면 그 브랜치)에서 `.phantom/marketplace.json` 파일 하나만 읽습니다.
형식과 필드는 [README](./README.md#repository-format)에 정리했습니다. 핵심은 이렇습니다.

- 꼭 있어야 하는 필드는 `id`와 `download`입니다.
- `download`에는 `https://` 주소를 쓰거나, 이 저장소 GitHub Releases의 `<태그>/<파일>`을 씁니다.
- `since`와 `until`은 호환되는 Phantom 빌드 범위입니다. 범위 밖의 버전은 목록에서 숨깁니다.
- `detect`에는 `group:artifact` 좌표를 적습니다. 프로젝트가 그 라이브러리를 쓰면 Phantom이 설치를 제안합니다.
- `detectFiles`에는 `Chart.yaml` 같은 파일 이름을 적습니다. 프로젝트에 그 파일이 있으면 Phantom이 설치를 제안합니다.

플러그인 폴더마다 소스, `build.gradle.kts`, `README.md`, `KOR.md`를 둡니다.

## 빌드와 배포

플러그인은 설치된 Phantom IDEA에 대고 컴파일합니다. 그 위치가 `gradle.properties`의 `phantomIde`입니다.
JDK 25가 필요하고, Phantom에 들어 있는 것을 씁니다.

```
./gradlew :db:test :db:buildPlugin          # zip은 db/build/distributions에 생깁니다
python3 tools/release.py db                 # 빌드, 테스트, marketplace.json 갱신
python3 tools/release.py db --publish       # 위 작업에 더해 zip을 담은 GitHub 릴리스 생성
```

릴리스한 뒤에는 `.phantom/marketplace.json`을 커밋하고 push합니다.

## 플러그인 추가

1. `<폴더>/build.gradle.kts`를 만들고 `plugins { id("phantom-plugin") }`와 `version`을 적습니다.
2. 코드는 `<폴더>/src/main/kotlin`에, `plugin.xml`은 `<폴더>/src/main/resources/META-INF`에 둡니다.
3. `settings.gradle.kts`에 `include("<폴더>")`를 추가하고, `.phantom/marketplace.json`에 `"source": "<폴더>"` 항목을 추가합니다.
4. `<폴더>/README.md`와 `<폴더>/KOR.md`를 쓰고, 위 표에 한 줄을 추가합니다.

## 라이선스

Apache License 2.0.
