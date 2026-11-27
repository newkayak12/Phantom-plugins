# Support for Spring Boot 변경 이력

## 미배포

- @ConfigurationProperties 클래스의 키를 메서드 본문을 고칠 때마다 다시 읽지 않는다. 선언·주석·기본값이 바뀔 때만 다시 읽는다. (#PR)
## 0.8.5 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for Spring Boot → Support for Spring Boot. 플러그인 id와 기능은 그대로다. (#136)
- Actuator 탭에서 Health가 아닌 탭으로 옮기면 5초마다 하던 health 요청을 멈춘다. (#114)
- Spring Cloud Function 정의 검사가 함수 빈 목록을 YAML 입력마다 다시 찾지 않는다. Java·Kotlin·Groovy 코드나 라이브러리가 바뀔 때만 다시 찾는다. (#119)

## 0.8.4 (2026-11-22)

- 이름을 바꿨다: Spring Boot Support → Phantom for Spring Boot. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.8.3 (2026-11-22)

- 입력 중 CPU를 덜 쓴다. Java/Kotlin을 고칠 때 라이브러리 설정 메타데이터 수천 개를 다시 합치고 정렬하지 않고, 빌드 출력 메타데이터도 바뀌었을 때만 다시 읽는다. 결과는 그대로다.

## 0.8.2 (2026-11-22)

- 입력 중 CPU를 덜 쓴다. `application.yml`을 고칠 때 코드 검색을, Java/Kotlin을 고칠 때 설정 파일 읽기를 다시 하지 않는다. 결과는 그대로다.

## 0.8.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)
