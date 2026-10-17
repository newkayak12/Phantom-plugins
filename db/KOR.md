# Phantom Database Console

`dev.phantom.db` · [English](./README.md)

MySQL, PostgreSQL, H2용 JDBC 콘솔입니다.

- `.sql` 파일에 문법 강조, 괄호 짝, 주석, 키워드 자동완성이 들어갑니다.
- **Ctrl+Enter**로 커서가 있는 문장을 실행합니다. 문장마다 왼쪽 여백에 실행 아이콘이 붙습니다.
- 결과는 정렬할 수 있는 그리드에 나오고, 오래 걸리는 쿼리는 취소할 수 있습니다.
- **Database** 툴 윈도우에서 데이터 소스와 스키마 트리를 봅니다. 트리는 펼칠 때 한 단계씩 불러옵니다.
- 드라이버는 프로젝트 의존성이나 로컬 Gradle/Maven 캐시에서 찾으므로 따로 내려받지 않습니다.
- 비밀번호는 IDE 비밀번호 저장소에만 저장하고, 프로젝트 파일에는 남기지 않습니다.
- IDE 시작 때는 아무것도 하지 않습니다. `.sql` 파일이나 툴 윈도우를 열 때 코드를 불러옵니다.

프로젝트가 MySQL, MariaDB, PostgreSQL, H2 드라이버에 의존하면 Phantom이 이 플러그인 설치를 제안합니다.

아직 없는 것: 스키마 비교, 마이그레이션 차이(Database Changes), 그리드에서 데이터 수정.
