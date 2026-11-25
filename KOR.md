# Phantom Plugins

[![분석 CI](https://github.com/newkayak12/Phantom-plugins/actions/workflows/analysis.yml/badge.svg?branch=main)](https://github.com/newkayak12/Phantom-plugins/actions/workflows/analysis.yml)
[![Strings 커버리지](https://codecov.io/gh/newkayak12/Phantom-plugins/branch/main/graph/badge.svg?flag=strings)](https://codecov.io/gh/newkayak12/Phantom-plugins)
[![Strings 품질 게이트](https://sonarcloud.io/api/project_badges/measure?project=newkayak12_Phantom-plugins&metric=alert_status&token=8d7c753364a763477e920082bd02b5dafde3994a)](https://sonarcloud.io/summary/new_code?id=newkayak12_Phantom-plugins)

[Phantom](https://github.com/newkayak12/Phantom)용 플러그인 모음입니다.
백엔드 기능은 필요할 때 설치하고, 일부 언어·도구 플러그인은 IDE 빌드에 기본 포함됩니다. [English](./README.md)

개인정보: Phantom과 플러그인이 네트워크로 보내는 내용은 Phantom의 [PRIVACY.md](https://github.com/newkayak12/Phantom/blob/main/PRIVACY.md)에 있습니다.

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
| Support for Spring | [spring](./spring/KOR.md) · [English](./spring/README.md) | 빈 주입 이동, 자동 주입 검사, Spring MVC 엔드포인트, Security 경로·표현식, Kafka·RabbitMQ·JMS 이동 (Java, Kotlin) |
| Support for Spring Boot | [spring-boot](./spring-boot/KOR.md) · [English](./spring-boot/README.md) | Spring Initializr로 새 Spring Boot 프로젝트 만들기, `application.yml` / `.properties` 키·값 자동완성과 문서 |
| Support for JPA | [jpa](./jpa/KOR.md) · [English](./jpa/README.md) | `@Query` 안의 JPQL, 파생 쿼리·`@Modifying` 검사, 엔티티 매핑 검사, 리포지토리·엔티티 이동 |
| Support for MyBatis | [mybatis](./mybatis/KOR.md) · [English](./mybatis/README.md) | 매퍼 인터페이스 ↔ XML 이동, statement / resultMap / property 참조와 오류, 빠진 statement 빠른 수정, `#{}` 자동완성, 매퍼 XML 안 SQL |
| Support for Reactive Streams | [reactive-streams](./reactive-streams/KOR.md) · [English](./reactive-streams/README.md) | 구독하지 않은 `Mono`/`Flux`, 연산자 안의 블로킹 호출 (Reactor, RxJava) |
| Support for Ktor | [ktor](./ktor/KOR.md) · [English](./ktor/README.md) | Ktor 라우트를 Endpoints 툴 윈도우에 표시 (`Route` 확장 함수 안의 라우트 포함) |
| Support for Kubernetes | [kubernetes](./kubernetes/KOR.md) · [English](./kubernetes/README.md) | Helm 템플릿: `.Values` 자동완성, 이동, 없는 값 경고 |
| Phantom Profiler | [profiler](./profiler/KOR.md) · [English](./profiler/README.md) | JFR 프로파일링: Profile 실행, 실행 중인 JVM 녹화, 플레임 그래프·콜 트리 보기 |
| Support for Bean Validation | [validation](./validation/KOR.md) · [English](./validation/README.md) | 모순된 범위, 맞지 않는 타입의 제약, Kotlin `@field:` 대상, 빠진 `@Valid` / `@Validated` |
| Phantom Migrations | [migrations](./migrations/KOR.md) · [English](./migrations/README.md) | Flyway 파일 이름과 중복 버전, Liquibase 중복 changeset과 없는 include |
| Support for OpenAPI | [openapi](./openapi/KOR.md) · [English](./openapi/README.md) | OpenAPI/Swagger 스펙의 `$ref` 이동·자동완성·오류, 스펙 ↔ 핸들러 거터 |
| Phantom HTTP Client | [http-client](./http-client/KOR.md) · [English](./http-client/README.md) | `.http` / `.rest` 요청을 에디터에서 실행: 환경 변수, `import` / `run`, 검사, 응답 창 |
| Phantom Icon Pack | [icons](./icons/KOR.md) · [English](./icons/README.md) | Material 파일·폴더 아이콘; Java 파일은 찻잔, Kotlin 파일은 로고, Java 클래스는 종류별 |
| Phantom Tab Groups | [tabs](./tabs/KOR.md) · [English](./tabs/README.md) | 브라우저처럼 에디터 탭 그룹: 이름, 색, 첫 탭으로 접기, 끌어서 넣고 빼기, 분할마다 |
| Phantom Workspaces | [workspaces](./workspaces/KOR.md) · [English](./workspaces/README.md) | 열린 프로젝트를 한 창의 탭처럼: Projects and Files에 저장소·worktree별 목록, 환영 창 대신 Home, 같은 크기·위치로 겹친 프로젝트 창 |
| Phantom AI Integration | [ai-integrations](./ai-integrations/KOR.md) · [English](./ai-integrations/README.md) | 프로젝트 옆의 Claude Code와 Codex: 한 도구 창의 worktree·세션 트리와 에이전트 터미널, 보드 에디터 탭, 대시보드, 사용량, 다른 기기 브라우저에서의 원격 제어 |
| Phantom AI Visualization | [ai-visualization](./ai-visualization/KOR.md) · [English](./ai-visualization/README.md) | Phantom AI Integration의 에이전트 세션을 아이소메트릭 픽셀 아트 사무실로: 프로젝트는 한 층, worktree는 책상 구역, 세션은 타이핑하고 기다리고 커피를 들고 자는 사람 한 명 |
| Phantom Strings | [strings](./strings/KOR.md) · [English](./strings/README.md) | 선택 영역이나 캐럿의 단어에 대소문자 전환·변환, 이스케이프/인코딩, 줄 정렬·정리 (Alt+M) |
| Phantom Rainbow | [rainbow](./rainbow/KOR.md) · [English](./rainbow/README.md) | 모든 언어에서 괄호를 중첩 깊이별로, 들여쓰기를 단계별로 색칠 |
| Phantom Ignore Files | [ignore](./ignore/KOR.md) · [English](./ignore/README.md) | .dockerignore, .npmignore, .helmignore 등을 .gitignore처럼 편집; ignore 파일 템플릿 |
| Phantom Bytecode Viewer | [bytecode](./bytecode/KOR.md) · [English](./bytecode/README.md) | 컴파일된 Java·Kotlin 클래스의 ASM 바이트코드, 에디터 캐럿과 동기화 |
| Support for WebP | [webp](./webp/KOR.md) · [English](./webp/README.md) | 네이티브 라이브러리 없이 Java로 디코딩하는 WebP 이미지 보기 |
| Phantom Modal Commit | [modal-commit](./modal-commit/KOR.md) · [English](./modal-commit/README.md) | Git·Mercurial에서 Commit 도구 창 대신 모달 커밋 대화상자 |
| Phantom TOML | [toml](./toml/KOR.md) · [English](./toml/README.md) | TOML 파일, Gradle 버전 카탈로그 지원의 바탕 (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom YAML | [yaml](./yaml/KOR.md) · [English](./yaml/README.md) | JSON Schema를 지원하는 YAML 파일. docker, kubernetes, openapi, migrations가 이것을 씀 (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Shell Script | [sh](./sh/KOR.md) · [English](./sh/README.md) | 셸 스크립트: 자동완성, 실행 구성, ShellCheck와 shfmt (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Lombok | [lombok](./lombok/KOR.md) · [English](./lombok/README.md) | 에디터에서 Project Lombok이 만드는 멤버, 인스펙션, Delombok (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Bytecode Decompiler for Java | [decompiler](./decompiler/KOR.md) · [English](./decompiler/README.md) | `.class` 파일을 소스 없이 열 때 디컴파일한 Java 소스 (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Code Coverage for Java | [coverage](./coverage/KOR.md) · [English](./coverage/README.md) | 플랫폼 또는 JaCoCo 러너로 Run with Coverage (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for TextMate Bundles | [textmate](./textmate/KOR.md) · [English](./textmate/README.md) | 다른 파일 형식을 TextMate와 VS Code 문법으로 하이라이팅 (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Markdown | [markdown](./markdown/KOR.md) · [English](./markdown/README.md) | Markdown 편집과 미리보기. Shell Script, Kotlin 등이 이것을 씀 (262.261112 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for GitHub | [github](./github/KOR.md) · [English](./github/README.md) | GitHub 계정, 클론, 프로젝트 공유, 풀 리퀘스트, Gist, Actions 스키마 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for EditorConfig | [editorconfig](./editorconfig/KOR.md) · [English](./editorconfig/README.md) | .editorconfig를 코드 스타일·들여쓰기·인코딩에 적용하고 파일 자체 편집도 지원 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Copyright | [copyright](./copyright/KOR.md) · [English](./copyright/README.md) | Velocity 템플릿으로 저작권 문구를 넣고, 저장이나 커밋 전에 갱신 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom HTML Tools | [htmltools](./htmltools/KOR.md) · [English](./htmltools/README.md) | HTML 검사(폐기된 마크업, 접근성, 이미지 크기, 링크)와 편집 기능 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for TestNG | [testng](./testng/KOR.md) · [English](./testng/README.md) | TestNG 실행 구성, 테스트 생성, 참조, 검사 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Internationalization for Java | [java-i18n](./java-i18n/KOR.md) · [English](./java-i18n/README.md) | Java 하드코딩 문자열 검사, I18nize, properties 키 참조 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Terminal | [terminal](./terminal/KOR.md) · [English](./terminal/README.md) | 셸 통합과 명령 자동 완성을 갖춘 Terminal 도구 창 (262.261113 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Grammar | [grammar](./grammar/KOR.md) · [English](./grammar/README.md) | 교정: LanguageTool로 텍스트·주석·문자열의 문법, 문체, 맞춤법 검사. 오픈 소스 코드만 사용 (Phantom Natural Languages, dev.phantom.grazie를 대신함) |
| Phantom Properties | [properties](./properties/KOR.md) · [English](./properties/README.md) | .properties 파일 강조, 자동 완성, 키 이름 변경, Resource Bundle 편집기, 검사 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for JUnit | [junit](./junit/KOR.md) · [English](./junit/README.md) | JUnit 3~6 실행 구성, 거터 실행, 테스트 생성, 검사 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Repository Search | [repository-search](./repository-search/KOR.md) · [English](./repository-search/README.md) | Maven·Gradle 빌드 파일의 의존성 자동 완성(로컬 인덱스·캐시 기반) (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom JSON | [json](./json/KOR.md) · [English](./json/README.md) | JSON·JSON5·JSON Lines 편집과 JSON Schema 검증·자동 완성 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Gradle | [gradle-core](./gradle-core/KOR.md) · [English](./gradle-core/README.md) | Gradle 프로젝트: 동기화, Gradle 도구 창, 실행 구성과 태스크, 데몬, Gradle 파일 편집 도움 |
| Support for Gradle (Java) | [gradle-java](./gradle-java/KOR.md) · [English](./gradle-java/README.md) | Gradle 프로젝트의 Java 지원: Java 가져오기, Gradle로 실행과 디버깅, Groovy DSL, 새 프로젝트 마법사, JPS 빌더 |
| Support for Gradle DSL | [gradle-dsl](./gradle-dsl/KOR.md) · [English](./gradle-dsl/README.md) | Gradle 빌드 파일(Groovy, Kotlin 스크립트, 버전 카탈로그)을 모델로 읽고 고치는 API (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Groovy Scripting | [groovy-scripting](./groovy-scripting/KOR.md) · [English](./groovy-scripting/README.md) | Groovy 스크립트 엔진과 Groovy 3 라이브러리 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Groovy | [groovy](./groovy/KOR.md) · [English](./groovy/README.md) | Groovy 언어 지원 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Groovy Live Templates | [groovy-live-templates](./groovy-live-templates/KOR.md) · [English](./groovy-live-templates/README.md) | groovyScript() 라이브 템플릿 매크로 (262.261116 빌드부터 Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Images | [images](./images/KOR.md) · [English](./images/README.md) | 이미지 뷰어와 편집 도구(확대, 격자, 체스판, 배경 이미지, 과학용 보기)와 SVG 언어 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Navigation Bar | [navbar](./navbar/KOR.md) · [English](./navbar/README.md) | 현재 파일 경로를 보여 주는 내비게이션 바 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Recent Files | [recentfiles](./recentfiles/KOR.md) · [English](./recentfiles/README.md) | 최근 파일, 최근 위치, 스위처 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Services View | [serviceview](./serviceview/KOR.md) · [English](./serviceview/README.md) | 서비스 도구 창과 실행 대시보드 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Endpoints | [endpoints](./endpoints/KOR.md) · [English](./endpoints/README.md) | Endpoints 도구 창: Spring, Ktor 등 플러그인이 알려 주는 HTTP 엔드포인트와 Generate HTTP Request (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom Grid | [grid](./grid/KOR.md) · [English](./grid/README.md) | 다른 플러그인이 표를 보여 주는 데이터 그리드(정렬, 필터, 셀 편집, 내보내기)와 CSV/TSV 파일의 표 보기 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Support for Git | [git](./git/KOR.md) · [English](./git/README.md) | IDE 안의 Git: 클론, 커밋, 푸시, 풀, 병합과 리베이스, 브랜치, Git 로그, 스태시, 스테이징 영역, 워크트리. Support for GitHub 등이 이것을 바탕으로 동작 (Phantom에 기본 포함되고 업데이트는 마켓플레이스로) |
| Phantom CPU Indicator | [cpu](./cpu/KOR.md) · [English](./cpu/README.md) | 상태바에 IDE·시스템 CPU, 호버하면 IDE가 CPU를 어디에 썼는지(인덱싱, 분석, GC, JIT) |
| Phantom Log Console | [logs](./logs/KOR.md) · [English](./logs/README.md) | JSON 로그를 읽기 쉬운 줄과 스택트레이스로, 레벨 색, traceId를 누르면 그 요청의 줄 목록 |
| Phantom Key-Value Console | [redis](./redis/KOR.md) · [English](./redis/README.md) | Redis 호환 서버(Redis, Valkey, KeyDB…)용 키 브라우저와 콘솔: 패턴으로 SCAN, 모든 타입의 값, redis-cli 형식 명령줄 |
| Support for QueryDSL | [querydsl](./querydsl/KOR.md) · [English](./querydsl/README.md) | Q클래스 경로에서 엔티티로 이동, 엔티티 필드 Find Usages에 QueryDSL 경로 포함, 오래된 Q클래스 경고 |
| Support for Testcontainers | [testcontainers](./testcontainers/KOR.md) · [English](./testcontainers/README.md) | 시작되지 않거나 테스트마다 시작하는 컨테이너, 버전 없는 이미지, static이어야 하는 Spring 멤버 |
| Support for Mermaid | [mermaid](./mermaid/KOR.md) · [English](./mermaid/README.md) | Mermaid 편집과 미리보기 (업스트림 플러그인을 기반으로 Phantom id로 빌드) |
| Phantom Local Shared Index | [local-shared-index](./local-shared-index/KOR.md) · [English](./local-shared-index/README.md) | 같은 프로젝트의 체크아웃 사이에서 이 컴퓨터의 인덱스 데이터를 재사용 (선택 설치) |
| Phantom Settings Repository | [sync](./sync/KOR.md) · [English](./sync/README.md) | JetBrains 계정 대신 내가 가진 git 저장소로 Backup and Sync (번들) |
| Phantom Theme | [theme](./theme/KOR.md) · [English](./theme/README.md) | Phantom의 기본 모양인 Phantom Dark와 Phantom Light (번들) |

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

Phantom이 설치되어 있지 않으면 Gradle 명령에 `-PplatformFromRepository=true`를 더합니다. 기여 지침은 [AGENTS.md](AGENTS.md)를 참고합니다.

플러그인은 설치된 Phantom에 대고 컴파일합니다. 그 위치가 `gradle.properties`의 `phantomIde`입니다.
JDK 25가 필요하고, Phantom에 들어 있는 것을 씁니다.

```
./gradlew :db:test :db:buildPlugin          # zip은 db/build/distributions에 생깁니다
python3 tools/release.py db                 # 빌드, 테스트, marketplace.json 갱신
python3 tools/release.py db --publish       # 위 작업에 더해 zip을 담은 GitHub 릴리스 생성
```

릴리스한 뒤에는 `.phantom/marketplace.json`을 브랜치에 커밋하고 pull request로 merge합니다. `main`은 pull request로만
바뀝니다(CLAUDE.md → Git).

## 커버리지

커버리지 배지는 Strings 플러그인만 측정한다. 위 배지에서 Codecov 리포트와 SonarQube 품질 게이트를 확인할 수 있다.

## 플러그인 추가

1. `<폴더>/build.gradle.kts`를 만들고 `plugins { id("phantom-plugin") }`와 `version`을 적습니다.
2. 코드는 `<폴더>/src/main/kotlin`에, `plugin.xml`은 `<폴더>/src/main/resources/META-INF`에 둡니다.
3. `settings.gradle.kts`에 `include("<폴더>")`를 추가하고, `.phantom/marketplace.json`에 `"source": "<폴더>"` 항목을 추가합니다.
4. `<폴더>/README.md`와 `<폴더>/KOR.md`를 쓰고, 위 표에 한 줄을 추가합니다.

## 라이선스

Apache License 2.0이고, 자체 LICENSE가 있는 플러그인 폴더는 그 라이선스를 따릅니다. 플러그인에 포함된 서드파티 코드와 그
라이선스 위치는 [NOTICE](./NOTICE)에 있고, 각 플러그인 jar의 `META-INF/THIRD-PARTY-NOTICES.txt`에 포함된 라이브러리 목록이
있습니다.
