# Support for Bean Validation
`dev.phantom.validation` · [English](./README.md)

Java와 Kotlin을 위한 Jakarta(그리고 javax) Bean Validation 검사입니다.

- **모순된 범위:** `@Min`이 `@Max`보다 크거나, `@DecimalMin`이 `@DecimalMax`보다 크거나, `@Size`/`@Length`의 min이 max보다 크거나 음수인 경우를 알려 줍니다.
- **맞지 않는 타입:** 숫자에 붙은 `@NotBlank`, 날짜에 붙은 `@Size`, `String`에 붙은 `@Past`처럼 검증기가 없는 조합입니다. 실행하면 `UnexpectedTypeException`으로 실패합니다.
- **Kotlin `@field:`:** `class SignUp(@NotBlank val name: String)`은 제약이 생성자 파라미터에 붙어서 `@Valid`가 검사하지 않습니다. 빠른 수정으로 `@field:NotBlank`로 바꿉니다.
- **빠진 `@Valid`:** 자체 제약이 있는 프로젝트 클래스(또는 그 컬렉션)를 담은 필드에 `@Valid`가 없으면 약한 경고를 냅니다.
- **빠진 `@Validated`:** 메서드 파라미터에 제약이 있는데 `@Validated`가 없는 Spring 서비스, 컴포넌트, 리포지토리(프로젝트가 만든 스테레오타입 애너테이션을 거친 것도 포함)에 약한 경고를 냅니다. 컨트롤러는 Spring Framework 6.1 이전에서만, 파라미터에 붙은 제약만 봅니다. `@Valid` 인자와 6.1부터의 컨트롤러 파라미터는 Spring MVC가 직접 검사합니다.

프로젝트가 Bean Validation API, Hibernate Validator, `spring-boot-starter-validation`을 쓰면 Phantom이 이 플러그인 설치를 제안합니다. 그 밖의 모듈에서는 아무것도 하지 않습니다.
