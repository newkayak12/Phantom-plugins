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
- **New Console**(트리, 도구 막대, Tools 메뉴, **Ctrl+Shift+Q** 또는 **Ctrl+Alt+Shift+Q**, macOS **Cmd+Shift+L** 또는 **Ctrl+Cmd+N**): 선택한 데이터 소스의 콘솔을 선택한 스키마로 하나 더 엽니다. `<이름>.sql`, `<이름> [1].sql`, … 순서로 Scratches and Consoles → Database Consoles에 만듭니다. 데이터 소스의 **consoles** 폴더와 **Navigate → Jump to Console…**에 나옵니다. 한 데이터 소스의 콘솔들은 콘솔 연결 하나를 같이 쓰므로, 수동 커밋 트랜잭션(과 Database Changes)도 콘솔마다가 아니라 데이터 소스마다 하나입니다.
- **JOIN 자동 완성**(DataGrip처럼): `JOIN` 뒤에서는 FROM에 이미 있는 테이블과 외래 키로(어느 방향이든) 이어진 테이블을 별칭과 `ON` 절까지 붙여 먼저 보여 줍니다(`customer c ON o.customer_id = c.id`). `JOIN t [별칭] ON` 뒤에서는 `t`와 앞의 모든 테이블을 잇는 조건 전체를, 외래 키에서 먼저, 그다음 열 이름으로 추측해(`x_id` = `x.id`, 양쪽의 같은 `*_id` 열) 보여 줍니다. 캐시된 메타데이터만 읽고, 없는 키는 백그라운드에서 불러와 다음 입력 때 나옵니다.
- **라이브 템플릿**(**SQL (Phantom)** 그룹, `.sql` 파일·콘솔·SQL 문자열): `sel`, `selc`(count), `selw`(WHERE), `ins`(테이블 열을 불러왔으면 열 목록을 채움), `upd`, `del`, `crt`. 테이블 이름은 자동 완성됩니다.
- 테이블, 뷰, 열의 **Find Usages와 Rename**: `.sql` 파일, 콘솔, Java·Kotlin의 SQL 문자열(`@Query`, JdbcTemplate, JdbcClient)에서 같은 테이블·열로 해석되는 곳과 JPA `@Table` / `@Column` / `@JoinColumn` 이름을 찾습니다. Rename은 SQL 텍스트만 바꾸고(따옴표 이름은 따옴표를, 따옴표 없는 이름은 대소문자 형태를 유지) 데이터베이스는 건드리지 않습니다. 대화상자에서 `ALTER … RENAME`을 실행하지 않고 데이터 소스 콘솔에 적거나 JPA 매핑 이름도 바꿀 수 있습니다. 둘 다 기본은 꺼짐입니다.
- **Unknown table / Unknown column** 검사(스키마를 불러온 뒤)와 빠른 수정: 테이블에 빠진 별칭 추가, 그 열이 있는 테이블로 한정. **코드 안 SQL 문자열의 SELECT \***(기본 꺼짐)는 열 목록으로 바꿀 수 있습니다.
- 데이터 소스의 **SSH/SSL** 탭: **SSH 터널**(호스트, 포트, 사용자; 비밀번호, 패스프레이즈가 있는 개인 키 파일, 실행 중인 OpenSSH 에이전트)이 `127.0.0.1:<빈 포트>`를 URL의 호스트와 포트로, URL에서 읽을 수 없으면 직접 적은 원격 호스트와 포트로 전달합니다. 터널은 데이터 소스마다 하나로 연결들이 같이 쓰고 연결과 함께 닫힙니다. **Test Connection**은 따로 터널을 엽니다. 모르는 호스트 키는 SHA256 지문을 보여 주고, 받아들이면 `~/.ssh/known_hosts`에 추가합니다. 바뀐 키는 거부합니다. **SSL**: 사용 여부, 모드(require, CA 검증, CA와 호스트 이름 검증), CA·클라이언트 인증서·키 파일을 내장 드라이버마다 그 드라이버의 속성으로 씁니다(MySQL `sslMode`, PostgreSQL `sslmode`/`sslrootcert`, SQL Server `encrypt`/`trustServerCertificate` 등). 다른 드라이버는 Properties 표를 쓰며, Properties 표가 항상 우선합니다.
- **Diagram**(트리 도구 막대나 메뉴, 스키마나 선택한 테이블에서): 테이블과 열(PK, FK 표시), 외래 키 선을 키 방향에 따라 층으로 배치해 편집기 탭에 그립니다. 끌어서 화면이나 테이블을 옮기고, 휠로 확대·축소하고, **Export as PNG**로 저장합니다. 테이블은 200개까지입니다.
- 테이블의 **Import Data from CSV…**: 구분자, 따옴표, 헤더 여부를 추측하고 바꿀 수 있으며, 처음 50행을 미리 보고, 열마다 CSV 열(헤더 이름, 없으면 위치)을 고르거나 뺍니다. 백그라운드에서 한 트랜잭션 안에 묶음 INSERT로 넣고(취소 가능), 잘못된 행이 있으면 전체를 되돌리고 그 줄 번호를 알려 줍니다. 수동 커밋 데이터 소스에서는 열린 트랜잭션에 들어가 Database Changes에서 기다립니다.
- **Explain Plan / Explain Analyze**: 계획을 비용, 행 수, 시간 열이 있는 트리로 보여 주고 가장 비싼 단계를 빨간색으로 표시합니다. 텍스트 형태는 두 번째 탭에 있습니다(PostgreSQL, MySQL/MariaDB는 JSON으로, MySQL의 EXPLAIN ANALYZE 트리와 H2 텍스트는 들여쓰기로 읽습니다).
- **결과 그리드**: 테이블 데이터 보기(와 테이블 하나에서 SELECT한 결과)에 **WHERE**, **ORDER BY** 입력란이 있으며, 테이블 컬럼을 완성하는 작은 SQL 편집기입니다. Enter는 첫 페이지부터 다시 조회하고, 드롭다운에 그 테이블의 이전 필터가 있으며, 필터를 건 SELECT는 원래 WHERE를 유지합니다. 테이블 데이터 보기에서 컬럼 헤더를 클릭하면 서버에서 정렬합니다(그 컬럼, 내림차순, 정렬 없음 순서). 상태 줄에는 **Transpose**(레코드를 열로), **Value Viewer**(선택한 셀을 그리드 옆에 전체로 표시: 줄 바꿈 텍스트, 보기 좋게 정리한 JSON, XML, 바이트는 16진 덤프와 파일로 저장, PNG/JPEG는 그림; 편집 가능한 결과의 텍스트 셀은 여기서 바꿀 수 있음), 선택 영역의 개수, 고유 값 수, 합계, 평균, 최솟값, 최댓값이 있습니다. **Copy As**(그리드 팝업 또는 상태 줄)는 선택 영역을 TSV, CSV, JSON, SQL INSERT(편집 대상 테이블로, 없으면 물어봄), Markdown, HTML로 복사합니다. **Export…**는 보이는 행을 같은 형식으로 저장하거나 복사하고, 테이블 데이터 보기에서는 테이블 전체(필터와 정렬 포함)를 백그라운드에서 페이지 단위로 파일에 씁니다. 실행 결과는 콘솔의 결과 탭(첫 SQL 줄이 제목)을 대체하며, 고정한 탭은 남습니다. 톱니 메뉴에 Pin Tab, Close Other Tabs, Close All Tabs가 있습니다.
- 비밀번호(SSH 비밀번호와 키 패스프레이즈 포함)는 IDE 비밀번호 저장소에만 저장하고, 프로젝트 파일에는 남기지 않습니다. DataGrip처럼 비밀번호 옆 **Save:**로 **Forever**(비밀번호 저장소), **Until restart**(메모리만), **Never**를 고릅니다. 비밀번호 저장소는 IDE 실행마다 한 번만 읽어서, 연결할 때마다 OS 키체인에 묻지 않습니다. 로그인이 거부되거나 "Never"인 비밀번호는 사용자와 비밀번호를 물어보고 다시 연결합니다.
- **DataGrip과 같은 단축키**: Database 트리의 모든 명령이 키맵에 있습니다(Settings → Keymap → Plug-ins → Phantom Database). 트리에서 **Cmd+N**/**Alt+Insert** 데이터 소스 추가, **Cmd+I**/**Shift+Enter** 편집, **Delete** 삭제, **Cmd+R**/**Ctrl+F5** 새로 고침, **Cmd+B**/**Ctrl+B** DDL, **Cmd+F6**/**Ctrl+F6** 테이블 수정, **Cmd+D**/**Ctrl+D** 스키마 비교, **Cmd+Alt+Shift+U**/**Ctrl+Alt+Shift+U** 다이어그램. 콘솔에서 **Cmd+F2**/**Ctrl+F2** 실행 중인 문장 취소, **Cmd+Alt+Shift+Enter**/**Ctrl+Alt+Shift+Enter** 커밋, Console.Open 키로 콘솔 이동.
- **그룹, 복사/붙여넣기, 복제**(DataGrip처럼): **Move to Group…**(F6)로 데이터 소스를 트리의 폴더에 넣습니다(`a/b`로 중첩, 폴더에서 쓰면 이름 변경). **Copy**/**Paste**는 데이터 소스를 XML로 다른 프로젝트나 IDE에 옮깁니다(비밀번호는 옮기지 않음). **Duplicate**(Cmd+D)는 새 id로 복사합니다.
- **SQL Generator…**(Ctrl+Alt+G)는 테이블의 DDL과 기본 키 기준 SELECT, INSERT, UPDATE, DELETE를 엽니다. **Copy DDL**(Ctrl+Shift+C / Ctrl+Alt+Shift+G)은 DDL을 복사합니다. **Select in Database View**(Alt+Shift+B)는 `.sql` 파일이나 콘솔에서 캐럿의 테이블이나 컬럼을, 없으면 데이터 소스를 트리에서 선택합니다.
- 편집 가능한 그리드에서 **Set NULL**(Ctrl+Alt+N), **Set DEFAULT**(Ctrl+Alt+D). Set DEFAULT는 DB가 넣은 값을 다시 읽어 셀에 보여줍니다.
- **Submit**(DataGrip처럼): 고친 셀과 지운 행은 변경으로 표시되고 **Submit**(Cmd/Ctrl+Enter)을 눌러야 실행됩니다(수정 먼저, 삭제 나중). **Submit and Commit**(Cmd/Ctrl+Alt+Shift+Enter)은 이어서 manual commit 트랜잭션을 커밋하고, **Revert Selected**(Ctrl+Alt+Z)는 선택한 변경을 버리고, **Preview Pending Changes**는 Submit이 실행할 문장을 보여줍니다. **Add Row**(Alt+Insert / Cmd+N)와 **Clone Row**(Cmd/Ctrl+D)는 그리드에 행을 추가해 바로 채우고 Submit 때 INSERT합니다(비운 열은 기본값). 그리드 팝업의 **Submit Changes Immediately**를 켜면 고칠 때마다 바로 씁니다. 그리드에서 **Select Row**(Shift+Space), **Select Column**(Ctrl+Space), **Edit Filter Criteria**(Ctrl+Alt+Shift+F)와 **Edit Ordering**은 WHERE / ORDER BY 칸으로 커서를 옮깁니다.
- **SSH 비밀번호나 키 패스프레이즈**가 없거나 거부되면 연결할 때 물어보고(**Save** 선택 가능) 터널을 다시 시도합니다.
- **세션**(DataGrip처럼): 연결된 데이터 소스는 트리에 초록 점이 붙습니다. **Disconnect**(트리에서 Cmd+F2 / Ctrl+F2)는 연결을 닫고, **Sessions…**는 열린 연결(콘솔 공유 연결, 콘솔 전용 연결, 스키마 트리 연결; 연 시각, 열린 트랜잭션)을 보여주며 하나 또는 전부 닫을 수 있습니다. **Single session mode**(데이터 소스 대화상자, 기본 켜짐)는 콘솔들이 연결 하나를 같이 씁니다. 끄면 콘솔마다 따로 연결합니다. Manual commit은 항상 하나를 같이 씁니다. Single session mode를 끄면 콘솔 위의 **Session:**(또는 팝업의 **Choose Session…**)으로 콘솔 전용 세션, 공유 세션, 다른 콘솔의 세션, 새 이름 붙은 세션 중에서 고릅니다. **Services** 도구 창의 **Database** 노드에 연결된 데이터 소스와 세션이 나옵니다(Disconnect, Close Session).
- **Schemas**(데이터 소스 대화상자, Options): 트리, 완성, 검사가 읽을 스키마(MySQL은 카탈로그)를 쉼표로 구분한 이름이나 glob(`app, sales_*`)으로 정하고, `!이름`으로 뺍니다. 비우면 전부 읽습니다. **Introspection level**: 1은 이름만(완성과 검사에 열·키를 읽지 않음), 2는 테이블을 쓸 때 열과 키를 읽음(기본), 3은 스키마를 읽을 때 모든 테이블의 열까지 미리 읽습니다.
- **Make Global**(Database 트리의 데이터 소스)은 데이터 소스를 IDE로 옮겨 모든 프로젝트에 비밀번호와 함께 보이게 합니다. **Move to Project**로 되돌립니다. 전역 데이터 소스는 트리에 *global*로 표시됩니다.
- IDE 시작 때는 아무것도 하지 않습니다. `.sql` 파일이나 툴 윈도우를 열 때 코드를 불러옵니다.

프로젝트가 프리셋 드라이버 중 하나에 의존하면 Phantom이 이 플러그인 설치를 제안합니다.

아직 없는 것: 조인이나 뷰 결과 수정, 뷰·루틴·외래 키 비교.
