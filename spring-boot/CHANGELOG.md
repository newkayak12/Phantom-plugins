# Support for Spring Boot 변경 이력

## 미배포

## 0.8.7 (2026-11-28)

- 고스트 모드에서는 설정 키 검사와 프로필·설정 거터 아이콘을 끈다. 설정 파일 색칠, 완성, 이동은 그대로다. (#204)

## 0.8.6 (2026-11-27)

- @ConfigurationProperties 키를 Spring Boot와 같은 생성자 규칙으로 읽는다. @ConstructorBinding 생성자, 하나뿐인 인자 있는 생성자, record, Kotlin 주 생성자로 바인딩하면 setter 키는 넣지 않고, 생성자가 여럿이거나 @Autowired면 setter로 읽는다. (#176)
- application_local.yml처럼 밑줄로 이어진 설정 파일도 설정 파일로 본다. spring.config.import의 상대 file: 경로를 가져오는 파일 옆에서 못 찾으면 프로젝트 폴더에서 찾는다. Spring 플러그인과 같은 규칙이다. (#176)
- 설정 키 검사가 YAML 블록 스칼라(|, >) 안의 줄을 키로 보고 알 수 없는 키나 폐기된 키로 표시하지 않는다. (#176)
- Actuator 탭의 문구를 리소스 번들로 옮겼다. 탭을 닫은 뒤에는 대기 중이던 화면 갱신을 하지 않고, 백그라운드 요청이 Swing 컴포넌트를 EDT 밖에서 읽지 않는다. (#176)
- application-<profile> 파일의 재정의 표시가 Java 입력마다 짝 파일을 다시 읽지 않는다. 설정 파일이 바뀌면 디스크에서 바뀐 경우도 다시 읽는다. (#176)
- 설정 키 줄과 @ConfigurationProperties 표시가 키를 찾을 때 모든 키를 훑지 않고 정렬된 색인에서 찾는다. (#176)
- 설정 키 요청마다 빌드 출력의 메타데이터 파일을 디스크에서 확인하지 않는다. IDE 빌드가 끝나면 바로, 그 밖에는 3초에 한 번만 확인한다. 라이브러리 메타데이터 캐시는 프로젝트별로 크기를 제한하고, 삭제된 모듈의 캐시는 남지 않는다. (#176)
- @ConfigurationProperties 클래스의 키를 메서드 본문을 고칠 때마다 다시 읽지 않는다. 선언·주석·기본값이 바뀔 때만 다시 읽는다. (#176)

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
