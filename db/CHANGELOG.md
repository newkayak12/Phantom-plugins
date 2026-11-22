# Phantom Database Console 변경 이력

## 미배포

## 0.25.10 (2026-11-22)

- 입력할 때 CPU를 덜 쓴다. JPA 스키마 검사가 Java·Kotlin 파일을 칠 때마다 Spring 설정 파일을 다시 읽지 않고, SQL `CREATE TABLE`의 엔티티 표시가 SQL을 칠 때마다 엔티티를 다시 찾지 않는다. 결과는 그대로다.

## 0.25.9 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)
