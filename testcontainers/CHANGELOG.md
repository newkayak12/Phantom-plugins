# Support for Testcontainers 변경 이력

## 미배포

## 0.1.4 (2026-11-27)

- `DockerImageName.parse("postgres").withTag("16")`을 태그 없는 이미지로 잘못 알리지 않는다. (#PR)
- 추상 기반 클래스의 `@Container`를 `@Testcontainers`가 없다고 알리지 않는다. 하위 클래스가 붙이는 경우가 많다. (#PR)
- Kotlin에서 object 안에 중첩된 클래스의 멤버를 static으로 보지 않는다. (#PR)
- 이미지 태그 검사가 호출을 해석하기 전에 이름과 인자로 먼저 걸러, 관계없는 호출을 해석하지 않는다. (#PR)

## 0.1.3 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for Testcontainers → Support for Testcontainers. 플러그인 id와 기능은 그대로다. (#136)
- Testcontainers가 클래스패스에 없는 모듈에서는 검사를 아예 돌리지 않는다. 전에는 이미지 태그 검사가 모든 Java·Kotlin 파일의 모든 호출을 해석했다. (#120)

## 0.1.2 (2026-11-22)

- 이름을 바꿨다: Testcontainers Support → Phantom for Testcontainers. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.1.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)
