# Support for Bean Validation 변경 이력

## 미배포

## 0.2.4 (2026-11-27)

- 빠진 `@Validated` 검사가 프로젝트가 만든 스테레오타입 애너테이션(`@Service`를 붙인 `@UseCase` 등)을 붙인 빈도 본다. Spring Framework 6.1 이전에서는 파라미터 제약이 있는 컨트롤러도 알린다. (#177)
- `@Target`에 `TYPE_PARAMETER`만 있는 제약을 생성자 파라미터에도 붙는 것으로 잘못 보고 `@field:`를 권하던 문제를 고쳤다. (#177)
- Kotlin 속성의 `@Valid`를 텍스트가 아니라 해석된 애너테이션으로 찾아, import 별칭으로 쓴 `@Valid`도 인식한다. 제약이 있는 클래스인지 판단한 결과는 캐시한다. (#177)

## 0.2.3 (2026-11-26)

- 표시 이름을 바꿨다: Phantom for Bean Validation → Support for Bean Validation. 플러그인 id와 기능은 그대로다. (#136)

## 0.2.2 (2026-11-22)

- 이름을 바꿨다: Bean Validation Support → Phantom for Bean Validation. Phantom으로 시작하고, 지원하는 프로젝트 이름은 뒤에 둔다. 플러그인 id와 기능은 그대로다.

## 0.2.1 (2026-11-20)

- 플러그인 jar에 `LICENSE`, `NOTICE`와 서드파티 라이선스 고지를 넣었다. (#45)
