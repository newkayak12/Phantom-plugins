# Phantom Database Console

`dev.phantom.db` · [English](./README.md)

MySQL, MariaDB, PostgreSQL, H2, AWS, Oracle, SQL Server 등을 위한 JDBC 콘솔이며, 드라이버를 직접 추가할 수 있습니다.

- `.sql` 파일에 문법 강조, 괄호 짝, 주석, 키워드 자동완성이 들어갑니다.
- **Ctrl+Enter**로 커서가 있는 문장을 실행합니다. 문장마다 왼쪽 여백에 실행 아이콘이 붙습니다.
- 결과는 정렬할 수 있는 그리드에 나오고, 오래 걸리는 쿼리는 취소할 수 있습니다.
- **Database** 툴 윈도우에서 데이터 소스와 스키마 트리를 봅니다. 트리는 펼칠 때 한 단계씩 불러옵니다.
- **드라이버는 데이터입니다.** 프리셋 14개(MySQL, MariaDB, PostgreSQL, H2, MySQL·PostgreSQL용 AWS JDBC Wrapper, AWS JDBC Driver for MySQL(`jdbc:mysql:aws:`), Amazon Redshift, Amazon Athena, Oracle, SQL Server, SQLite, ClickHouse, Snowflake)와 직접 만든 드라이버를 씁니다. **Drivers…** 대화상자(도구 창, 또는 오류의 **Configure Drivers…**)에서 드라이버를 추가, 복제, 삭제, 초기화합니다. jar 파일이나 Maven 좌표, 버전(`LATEST`는 다운로드 때 고정, **Check for updates**), URL 템플릿, URL 접두사, 기본 속성, 방언, 추가 Maven 저장소(Maven Central이 항상 먼저)를 정할 수 있고, 드라이버 클래스는 jar에서 자동으로 찾습니다.
- **jar를 찾는 순서**: 직접 고른 jar, 드라이버 파일, 프로젝트 라이브러리, 로컬 Gradle/Maven 캐시, 내려받은 jar(IDE 시스템 디렉터리). 기존 MySQL, MariaDB, PostgreSQL, H2 설정은 내려받기 없이 그대로 동작합니다.
- **내려받기는 요청할 때만** 합니다: **Download**, **Test Connection**, 알림의 **Download driver**. 전이 의존성을 함께 풀고 SHA-1을 확인합니다.
- 데이터 소스 대화상자에는 드라이버 콤보(URL을 붙여넣으면 드라이버가 선택됨), 호스트/포트/데이터베이스 입력 또는 URL 직접 입력, 인증(사용자/비밀번호, 없음, **AWS IAM**: AWS 프로필과 리전, 드라이버 자체 IAM 지원에 위임), 드라이버 속성 표가 있습니다.
- **SQL 지원이 없는 데이터베이스**(방언 "Other": Redshift, Athena, Oracle, SQL Server, SQLite, ClickHouse, Snowflake 기본값): 콘솔, 결과 그리드, 스키마 트리, 내보내기는 되고, 방언 검사, EXPLAIN, DDL, 테이블 수정, 스키마 비교, 그리드 수정은 꺼집니다.
- 드라이버와 데이터 소스에 업체 아이콘이 붙습니다.
- **팀 공유**: 0.10.0 사용자를 위해 데이터 소스에 `dbms`도 계속 저장합니다. 동료 IDE에 없는 드라이버 id는 URL 접두사로 대신 찾고, 없으면 "정의되지 않음" 오류와 **Configure Drivers…**가 나옵니다.
- **수동 커밋**(데이터 소스 설정): 문장을 열린 트랜잭션 안에서 실행합니다. **Database Changes** 도구 창에서 그 안에 든 변경을 보고 **Commit**이나 **Roll Back**을 누릅니다. 콘솔에 직접 친 `COMMIT`/`ROLLBACK`도 되고, 커밋하지 않은 채 프로젝트를 닫으면 먼저 묻습니다.
- **그리드에서 바로 수정**: 테이블 하나에서 기본키까지 가져온 결과는 그 자리에서 고칠 수 있습니다. 셀을 바꾸면 기본키 기준 `UPDATE`가, 행을 지우면 `DELETE`가 실행됩니다. 행 추가와 행 복제는 콘솔에 `INSERT`를 열어 마저 채우게 합니다. 수동 커밋 데이터 소스에서는 Database Changes에 대기합니다.
- 스키마에서 **Compare With…**: 두 스키마(다른 데이터 소스여도 됨)의 테이블, 열, 키, 인덱스를 diff 창에서 비교하고, 상대 쪽을 맞추는 SQL을 그쪽 콘솔에 엽니다. 삭제 문장은 주석으로 적습니다.
- **데이터 소스 색상**(DataGrip처럼): 데이터 소스 대화상자에서 색을 고릅니다. 그 데이터 소스의 콘솔과 `.sql` 파일은 편집기 배경과 탭에, Database 트리의 행은 옅은 바탕으로, 결과 탭은 위쪽 띠로 그 색을 보여 줍니다. 데이터 소스를 저장하면 바로 다시 칠합니다. 색은 데이터 소스와 함께 저장되어 팀이 같이 씁니다.
- **콘솔마다 스키마**: 콘솔이나 `.sql` 파일 위의 **Schema: …**(또는 편집기 메뉴의 **Choose Schema…**)로 실행할 스키마를 고릅니다. MySQL과 MariaDB에서는 카탈로그입니다. 문장은 그 스키마에서 실행되고, 자동 완성·이동·검사에서 스키마를 붙이지 않은 테이블 이름도 그 스키마에서 먼저 찾습니다. 파일마다 따로 기억합니다.
- **탐색기**(DataGrip처럼): 스키마 아래 객체를 폴더로 묶습니다. tables, views, materialized views(드라이버가 알려 줄 때), routines, triggers, sequences. 테이블 아래에는 열(타입, PK, FK, NOT NULL), keys, foreign keys, indexes, triggers가 있습니다. 폴더는 펼칠 때 불러오고 **Refresh** 전까지 캐시에 둡니다. 더블클릭이나 **F4**로 테이블은 데이터를, 뷰·루틴·트리거는 DDL을 열고, 외래 키는 참조하는 테이블로 이동합니다. 트리에서 바로 타이핑하면 찾아가고(speed search), 위의 필터 칸은 불러온 객체를 이름으로 좁히고 빈 폴더를 숨깁니다. 도구 막대의 **Show System Schemas**로 `information_schema`, `pg_catalog` 같은 시스템 스키마도 봅니다.
- **Navigate → Database Object…**(Search Everywhere의 **Database** 탭도 같음): 트리가 불러온 테이블, 뷰, 루틴, 열을 모든 데이터 소스에서 찾고, 데이터 소스와 스키마를 옆에 보여 줍니다. 접속하지 않습니다. 고르면 트리에서 선택하고 데이터나 DDL을 엽니다.
- **New Console**(트리, 도구 막대, Tools 메뉴, **Ctrl+Alt+Shift+Q**, macOS **Ctrl+Cmd+N**): 선택한 데이터 소스의 콘솔을 선택한 스키마로 하나 더 엽니다. `<이름>.sql`, `<이름> [1].sql`, … 순서로 Scratches and Consoles → Database Consoles에 만듭니다. 데이터 소스의 **consoles** 폴더와 **Navigate → Jump to Console…**에 나옵니다. 한 데이터 소스의 콘솔들은 콘솔 연결 하나를 같이 쓰므로, 수동 커밋 트랜잭션(과 Database Changes)도 콘솔마다가 아니라 데이터 소스마다 하나입니다.
- **JOIN 자동 완성**(DataGrip처럼): `JOIN` 뒤에서는 FROM에 이미 있는 테이블과 외래 키로(어느 방향이든) 이어진 테이블을 별칭과 `ON` 절까지 붙여 먼저 보여 줍니다(`customer c ON o.customer_id = c.id`). `JOIN t [별칭] ON` 뒤에서는 `t`와 앞의 모든 테이블을 잇는 조건 전체를, 외래 키에서 먼저, 그다음 열 이름으로 추측해(`x_id` = `x.id`, 양쪽의 같은 `*_id` 열) 보여 줍니다. 캐시된 메타데이터만 읽고, 없는 키는 백그라운드에서 불러와 다음 입력 때 나옵니다.
- **라이브 템플릿**(**SQL (Phantom)** 그룹, `.sql` 파일·콘솔·SQL 문자열): `sel`, `selc`(count), `selw`(WHERE), `ins`(테이블 열을 불러왔으면 열 목록을 채움), `upd`, `del`, `crt`. 테이블 이름은 자동 완성됩니다.
- 테이블, 뷰, 열의 **Find Usages와 Rename**: `.sql` 파일, 콘솔, Java·Kotlin의 SQL 문자열(`@Query`, JdbcTemplate, JdbcClient)에서 같은 테이블·열로 해석되는 곳과 JPA `@Table` / `@Column` / `@JoinColumn` 이름을 찾습니다. Rename은 SQL 텍스트만 바꾸고(따옴표 이름은 따옴표를, 따옴표 없는 이름은 대소문자 형태를 유지) 데이터베이스는 건드리지 않습니다. 대화상자에서 `ALTER … RENAME`을 실행하지 않고 데이터 소스 콘솔에 적거나 JPA 매핑 이름도 바꿀 수 있습니다. 둘 다 기본은 꺼짐입니다.
- **Unknown table / Unknown column** 검사(스키마를 불러온 뒤)와 빠른 수정: 테이블에 빠진 별칭 추가, 그 열이 있는 테이블로 한정. **코드 안 SQL 문자열의 SELECT \***(기본 꺼짐)는 열 목록으로 바꿀 수 있습니다.
- 비밀번호는 IDE 비밀번호 저장소에만 저장하고, 프로젝트 파일에는 남기지 않습니다.
- IDE 시작 때는 아무것도 하지 않습니다. `.sql` 파일이나 툴 윈도우를 열 때 코드를 불러옵니다.

프로젝트가 프리셋 드라이버 중 하나에 의존하면 Phantom이 이 플러그인 설치를 제안합니다.

아직 없는 것: 조인이나 뷰 결과 수정, 뷰·루틴·외래 키 비교.
