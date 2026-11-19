# Phantom Migrations

`dev.phantom.migrations` · [English](./README.md)

Flyway와 Liquibase 검사입니다.

- **Flyway 파일 이름:** `db/migration`과 `spring.flyway.locations`로 지정한 위치(가져온 YAML 파일의 설정 포함)에서, Flyway가 건너뛰거나 거부하는 이름을 알려 줍니다. `V1_init.sql`(밑줄 하나), `v1__init.sql`(소문자 접두사), `V1__.sql`(설명 없음)이 그 예입니다.
- **Flyway 중복 버전:** 버전은 숫자로 비교하므로 `V1_16`, `V1.16`, `V1.016`은 같은 버전입니다. 버전 사이가 비는 것은 문제가 아니어서 알리지 않습니다.
- **Liquibase changelog**(XML, YAML, formatted SQL): 한 changelog 안에서 같은 id와 author를 두 번 쓴 changeset, 존재하지 않는 `include` / `includeAll` 경로를 알려 줍니다.

프로젝트가 `flyway-core`나 `liquibase-core`를 쓰면 Phantom이 이 플러그인 설치를 제안합니다. 그 밖의 모듈에서는 아무것도 하지 않습니다.

아직 없는 것: 마이그레이션 실행, 마이그레이션 목록 창.
