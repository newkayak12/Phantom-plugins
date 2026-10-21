# Phantom Database Console

`dev.phantom.db` · [English](./README.md)

MySQL, MariaDB, PostgreSQL, H2, AWS, Oracle, SQL Server 등을 위한 JDBC 콘솔이며, 드라이버를 직접 추가할 수 있습니다.

- `.sql` 파일에 문법 강조, 괄호 짝, 주석, 키워드 자동완성이 들어갑니다.
- **Ctrl+Enter**로 커서가 있는 문장을 실행합니다. 문장마다 왼쪽 여백에 실행 아이콘이 붙습니다.
- 결과는 정렬할 수 있는 그리드에 나오고, 오래 걸리는 쿼리는 취소할 수 있습니다.
- **Database** 툴 윈도우에서 데이터 소스와 스키마 트리를 봅니다. 트리는 펼칠 때 한 단계씩 불러옵니다.
- **드라이버는 데이터입니다.** 프리셋 13개(MySQL, MariaDB, PostgreSQL, H2, MySQL·PostgreSQL용 AWS JDBC Wrapper, Amazon Redshift, Amazon Athena, Oracle, SQL Server, SQLite, ClickHouse, Snowflake)와 직접 만든 드라이버를 씁니다. **Drivers…** 대화상자(도구 창, 또는 오류의 **Configure Drivers…**)에서 드라이버를 추가, 복제, 삭제, 초기화합니다. jar 파일이나 Maven 좌표, 버전(`LATEST`는 다운로드 때 고정, **Check for updates**), URL 템플릿, URL 접두사, 기본 속성, 방언, 추가 Maven 저장소(Maven Central이 항상 먼저)를 정할 수 있고, 드라이버 클래스는 jar에서 자동으로 찾습니다.
- **jar를 찾는 순서**: 직접 고른 jar, 드라이버 파일, 프로젝트 라이브러리, 로컬 Gradle/Maven 캐시, 내려받은 jar(IDE 시스템 디렉터리). 기존 MySQL, MariaDB, PostgreSQL, H2 설정은 내려받기 없이 그대로 동작합니다.
- **내려받기는 요청할 때만** 합니다: **Download**, **Test Connection**, 알림의 **Download driver**. 전이 의존성을 함께 풀고 SHA-1을 확인합니다.
- 데이터 소스 대화상자에는 드라이버 콤보(URL을 붙여넣으면 드라이버가 선택됨), 호스트/포트/데이터베이스 입력 또는 URL 직접 입력, 인증(사용자/비밀번호, 없음, **AWS IAM**: AWS 프로필과 리전, 드라이버 자체 IAM 지원에 위임), 드라이버 속성 표가 있습니다.
- **SQL 지원이 없는 데이터베이스**(방언 "Other": Redshift, Athena, Oracle, SQL Server, SQLite, ClickHouse, Snowflake 기본값): 콘솔, 결과 그리드, 스키마 트리, 내보내기는 되고, 방언 검사, EXPLAIN, DDL, 테이블 수정, 스키마 비교, 그리드 수정은 꺼집니다.
- 드라이버와 데이터 소스에 업체 아이콘이 붙습니다.
- **팀 공유**: 0.10.0 사용자를 위해 데이터 소스에 `dbms`도 계속 저장합니다. 동료 IDE에 없는 드라이버 id는 URL 접두사로 대신 찾고, 없으면 "정의되지 않음" 오류와 **Configure Drivers…**가 나옵니다.
- **수동 커밋**(데이터 소스 설정): 문장을 열린 트랜잭션 안에서 실행합니다. **Database Changes** 도구 창에서 그 안에 든 변경을 보고 **Commit**이나 **Roll Back**을 누릅니다. 콘솔에 직접 친 `COMMIT`/`ROLLBACK`도 되고, 커밋하지 않은 채 프로젝트를 닫으면 먼저 묻습니다.
- **그리드에서 바로 수정**: 테이블 하나에서 기본키까지 가져온 결과는 그 자리에서 고칠 수 있습니다. 셀을 바꾸면 기본키 기준 `UPDATE`가, 행을 지우면 `DELETE`가 실행됩니다. 행 추가와 행 복제는 콘솔에 `INSERT`를 열어 마저 채우게 합니다. 수동 커밋 데이터 소스에서는 Database Changes에 대기합니다.
- 스키마에서 **Compare With…**: 두 스키마(다른 데이터 소스여도 됨)의 테이블, 열, 키, 인덱스를 diff 창에서 비교하고, 상대 쪽을 맞추는 SQL을 그쪽 콘솔에 엽니다. 삭제 문장은 주석으로 적습니다.
- 비밀번호는 IDE 비밀번호 저장소에만 저장하고, 프로젝트 파일에는 남기지 않습니다.
- IDE 시작 때는 아무것도 하지 않습니다. `.sql` 파일이나 툴 윈도우를 열 때 코드를 불러옵니다.

프로젝트가 프리셋 드라이버 중 하나에 의존하면 Phantom이 이 플러그인 설치를 제안합니다.

아직 없는 것: 조인이나 뷰 결과 수정, 뷰·루틴·외래 키 비교.
