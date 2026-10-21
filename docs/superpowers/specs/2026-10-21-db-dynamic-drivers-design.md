# db: 동적 JDBC 드라이버 (DataGrip 수준 드라이버 관리)

날짜: 2026-10-21 · 대상 플러그인: `db` (현재 0.10.0)

## 왜

AWS 쪽 드라이버(AWS Advanced JDBC Wrapper, Redshift, Athena 등)로 접속이 안 된다. 원인은 현재 구조 자체다.

- `Dbms` enum(`db/src/main/kotlin/dev/phantom/db/core/DataSources.kt:30`)이 MySQL/MariaDB/PostgreSQL/H2 네 개로 닫혀 있고,
  DBMS마다 드라이버 클래스·URL 접두사·Maven 좌표가 하나씩 고정돼 있다. `jdbc:aws-wrapper:mysql://`, `jdbc:redshift:`,
  `jdbc:awsathena:` 같은 URL은 갈 곳이 없다.
- 드라이버를 jar **하나**만 로드한다(`DbDrivers.newest` → `listOf(best.first)`). AWS 드라이버는 AWS SDK 등 전이 의존 jar가 필요하다.
- 다운로드가 없다. 프로젝트 라이브러리와 로컬 Gradle/Maven 캐시에 이미 있는 jar만 쓴다.

사용자 요구: "그냥 동적으로 처리할 수 있게 DataGrip 급으로". 기준은 DataGrip의 Drivers 설정이다.

## 목표

1. **드라이버 = 데이터.** 드라이버 정의(이름, 드라이버 클래스, URL 템플릿, 드라이버 파일, 기본 속성, SQL 방언)를 코드가 아니라
   설정으로 다룬다. 내장 드라이버도 같은 형식의 기본값일 뿐이다.
2. **사용자 드라이버 추가/복제/편집.** DataGrip처럼 아무 JDBC 드라이버나 jar나 Maven 좌표로 등록해 쓸 수 있다.
3. **Maven 좌표 + 전이 의존 다운로드.** `group:artifact:version`을 주면 의존 jar까지 받아 IDE 시스템 디렉터리에 캐시한다.
   버전 선택(최신/지정) 가능. 기존 "프로젝트 라이브러리 → 로컬 Gradle/Maven 캐시" 탐색은 다운로드보다 먼저 유지한다.
4. **내장 프리셋 확장.** 기존 4개 + AWS Advanced JDBC Wrapper(MySQL/PostgreSQL), Amazon Redshift, Amazon Athena,
   Oracle, SQL Server, SQLite, ClickHouse, Snowflake. (프리셋은 정의 데이터만 추가하는 것이라 저렴하다.)
5. **알 수 없는 DBMS도 기본 기능은 동작.** 콘솔 실행, 결과 그리드, 스키마 트리(JDBC `DatabaseMetaData`), 내보내기는
   드라이버 종류와 무관하게 동작해야 한다. DBMS 전용 기능(방언 인스펙션, EXPLAIN, DDL 생성, 그리드 수정 SQL, 스키마 비교)은
   방언이 알려진 경우에만 켠다.
6. **드라이버 속성 / 인증.** 드라이버 속성(Properties) 표 편집. 인증 방식 선택: 사용자/비밀번호, 인증 없음,
   **AWS IAM**(AWS 프로필·리전 지정, 드라이버 네이티브 IAM 기능에 속성으로 위임 — Wrapper `wrapperPlugins=iam`,
   Redshift `jdbc:redshift:iam://` 등). 비밀 값은 계속 PasswordSafe에만.
7. **기존 데이터 소스 무손실 마이그레이션.** 기존 `.idea/phantomDataSources.xml`의 `dbms` 필드를 가진 데이터 소스가
   그대로 열리고 동작해야 한다.

## 비목표 (이번 범위 아님)

- SSH 터널, SSL 인증서 UI (다음 단계 후보. 단, SSL은 드라이버 속성으로 이미 가능)
- 새 DBMS용 방언 인스펙션·파서 확장 (Oracle/SQL Server 문법 지원 등) — 별도 프로젝트
- 조인/뷰 결과 수정, 뷰·루틴·FK 비교 (README의 "아직 없는 것")
- AWS SDK를 플러그인에 번들해 직접 IAM 토큰 생성하기 (드라이버에 위임)

## 반(反)목표 — 이러면 실패

- IDE 시작 시 무언가 하거나 네트워크 접근 (현재 원칙 "Nothing runs at startup" 유지). 다운로드는 사용자가 접속/테스트/다운로드를
  눌렀을 때만, 백그라운드 작업 + 진행률 + 취소 가능.
- 기존 MySQL/PostgreSQL/H2 사용자의 동작이 바뀌거나 재설정이 필요해지는 것.
- 비밀번호/AWS 시크릿이 프로젝트 파일에 저장되는 것.
- 드라이버 jar가 IDE 클래스패스를 오염시키는 것 (드라이버별 독립 클래스로더 원칙 유지).

## 제약

- `phantom.db.core` 모듈은 `com.intellij.modules.lang` + grid만 의존한다. Java 플러그인(`JarRepositoryManager` 등)에 기댈 수 없다.
  → Maven 해석은 플랫폼 `HttpRequests` 기반의 자체 최소 리졸버로 한다.
- 드라이버마다 독립 `URLClassLoader`(부모: 플랫폼 로더), `DriverManager` 우회 직접 호출 — 현행 유지(`DbConnections.kt:42`).
- 테스트는 네트워크 없이 돌아야 한다 (CI). 리졸버는 로컬 파일 기반 가짜 저장소로 테스트한다.

## 설계 (결정된 부분)

### 드라이버와 방언 분리

현재 `Dbms`가 "어떤 드라이버를 로드할지"와 "어떤 SQL 방언인지" 두 역할을 다 한다. 이를 나눈다.

- **`Dbms` enum은 방언으로 남긴다** (SQL 인텔리전스 97곳이 이를 참조). `GENERIC` 값을 추가한다:
  방언을 모를 때. `GENERIC`에서는 방언 전용 기능을 끈다.
  (enum 이름을 `SqlDialect`로 바꿀지는 빌더 판단. 바꾸지 않는 쪽이 diff가 작다.)
- **`DbDriverDefinition`** (새 데이터): `id`, `name`, `driverClass`, `urlTemplates`(DataGrip 식 `{host}`, `{port}`, `{database}`
  플레이스홀더 + 원문 URL 모드), `urlPrefixes`(URL로 드라이버 자동 판별용), `artifacts`(Maven 좌표 목록, 버전 포함/`LATEST`),
  `files`(직접 지정 jar/디렉터리), `defaultProperties`, `dialect: Dbms`, `icon`, `authKinds`.
- **`DbDataSource`**: `driverId` 필드 추가, `properties`(Map) 추가, `authKind` + AWS 프로필/리전 필드 추가.
  `dbms`는 하위 호환을 위해 읽되, 방언은 `driver.dialect`에서 가져온다.

### 드라이버 레지스트리

- 내장 정의: 플러그인 리소스의 JSON/XML 하나로 둔다 (코드가 아닌 데이터 — 프리셋 추가가 데이터 변경만으로 되게).
- 사용자 정의 및 내장 정의 오버라이드: **애플리케이션 수준** `PersistentStateComponent` (DataGrip처럼 프로젝트 간 공유).
- 내장 정의는 "Reset to defaults" 가능, 삭제 불가(숨김은 빌더 판단).

### 드라이버 파일 해석 순서

1. 정의/데이터 소스에 직접 지정된 jar
2. 프로젝트 라이브러리 (기존 로직, 여러 jar 지원으로 확장)
3. 로컬 Gradle/Maven 캐시 (기존 로직) — 단, 전이 의존까지 캐시에서 모두 찾을 수 있을 때만 사용
4. 플러그인 다운로드 캐시 (`PathManager.getSystemPath()/phantom-db/drivers/...`)
5. 없으면 다운로드 제안 (오류 메시지 안의 "Download" 링크 + 데이터 소스 다이얼로그의 버튼)

### Maven 리졸버

- 저장소: Maven Central 기본 + 설정에서 저장소 URL 추가 가능 (사내 Nexus 등, 인증은 빌더 판단 — 최소한 URL만).
- POM 해석 범위: `compile`/`runtime` 스코프, `parent` POM, `properties` 치환, `dependencyManagement`(BOM `import` 포함),
  `exclusions`, `optional=true` 제외. 버전 충돌은 가장 가까운 선언 우선(Maven nearest-wins).
- `maven-metadata.xml`로 최신 버전 조회. SHA-1 체크섬 검증.
- 결과(jar 목록)를 정의+버전별로 캐시해 두고 재해석하지 않는다.

### UI

- **Drivers 설정 화면**: 데이터 소스 다이얼로그에서 접근(DataGrip의 Data Sources and Drivers처럼 "Drivers" 탭/목록).
  목록(내장+사용자), 추가/복제/삭제, 편집: 이름, 클래스(jar 안에서 `java.sql.Driver` 구현 자동 탐색 후 선택), 파일 목록(jar 추가,
  Maven 좌표 추가, 버전 선택, Download 버튼과 상태), URL 템플릿, 기본 속성, 방언.
- **데이터 소스 다이얼로그**: DBMS 콤보 → 드라이버 콤보. 호스트/포트/DB 필드 ↔ URL 원문 전환. 인증 방식 콤보.
  Properties 표. 드라이버 파일 상태 + Download 링크. Test Connection은 필요 시 다운로드 후 시도.
- URL을 붙여 넣으면 `urlPrefixes`로 드라이버 자동 선택 (기존 `Dbms.forUrl`의 일반화, `jdbc:p6spy:` 처리 유지).

### 마이그레이션

- 기존 데이터 소스(`dbms` 있음, `driverId` 없음) → 같은 DBMS의 내장 드라이버 id로 매핑. `driverJars`는 데이터 소스 수준
  직접 지정 파일로 유지.
- 기존 동작(프로젝트 의존성·로컬 캐시에서 찾기)은 다운로드 없이 그대로 성공해야 한다.

### 플러그인 추천 트리거

`projectHasDriverLibrary`가 레지스트리의 모든 내장 정의 좌표를 보도록 확장 (AWS Wrapper 의존 프로젝트에서도 제안).

### 리뷰 후 확정 사항

- **내장 4개 드라이버의 단일 jar 폴백 유지.** 2·3단계(프로젝트 라이브러리, 로컬 캐시)에서 내장 MySQL/MariaDB/PostgreSQL/H2
  드라이버는 전이 의존이 다 없더라도 현재처럼 드라이버 jar 하나로 로드한다(protobuf 등은 실제 접속에 필요 없음).
  "전이 의존이 모두 있어야 사용" 규칙은 Maven 좌표 기반의 새 드라이버에만 적용. 기존 사용자에게 다운로드 요구 금지.
- **팀 공유 호환.** 데이터 소스는 계속 `dbms`(방언)를 저장한다 → 0.10.0 사용자도 읽을 수 있다. `driverId`가 이 IDE에 없으면
  URL 접두사로 내장 드라이버를 찾아 쓰고, 그것도 없으면 "드라이버 '<id>' 없음 — Drivers에서 추가" 오류 + 설정 열기 링크.
  드라이버 정의를 데이터 소스에 복제 저장하지는 않는다.
- **프리셋 방언.** AWS Wrapper(MySQL) → `MYSQL`, AWS Wrapper(PostgreSQL) → `POSTGRESQL` (엔진이 같음).
  Redshift, Athena, Oracle, SQL Server, SQLite, ClickHouse, Snowflake → `GENERIC` (잘못된 SQL 생성 방지가 우선).
  사용자는 드라이버 편집에서 방언을 바꿀 수 있다.
- **다운로드 시점.** 콘솔 실행/트리 펼치기 중 드라이버가 없으면 자동 다운로드하지 않고 알림(“Download driver” 액션)을 띄운다.
  Test Connection과 Download 버튼만 명시적 다운로드. `LATEST`는 다운로드 시점에 한 번 해석해 그 버전을 고정 저장하고,
  매 접속마다 온라인 확인하지 않는다. 새 버전 확인은 드라이버 편집 화면에서 사용자가 누를 때만.

## 빌더에게 맡기는 것

- 클래스/파일 이름, 패키지 배치, 내장 정의 파일 형식(JSON vs XML)
- 설정 UI의 정확한 배치 (DataGrip과 비슷한 목록+상세 구성이면 됨)
- `GENERIC`일 때 각 방언 전용 기능을 숨길지 비활성화할지
- 다운로드 캐시 디렉터리 레이아웃, 리졸버 동시성
- 사설 저장소 인증 지원 여부

## 검증 기준

- 기존 테스트 전부 통과, 기존 데이터 소스 XML이 그대로 로드됨 (마이그레이션 테스트)
- 리졸버: 로컬 가짜 저장소로 parent/BOM/properties/exclusions/optional/nearest-wins 단위 테스트
- 로컬 캐시에 있는 H2를 "사용자 정의 드라이버"로 등록해 접속·쿼리·트리 탐색 (GENERIC 방언 경로)
- 수동 검증: AWS Advanced JDBC Wrapper(MySQL) 프리셋으로 다운로드 → Test Connection 성공까지

## 내 판단으로 정한 것 (확인 필요)

- 범위를 접속/드라이버 계층으로 한정, SSH 터널·새 방언 지원은 제외
- IAM은 AWS SDK 번들 대신 드라이버 네이티브 기능에 위임
- 드라이버 정의는 애플리케이션 수준(프로젝트 간 공유)
- 버전은 `0.11.0`
