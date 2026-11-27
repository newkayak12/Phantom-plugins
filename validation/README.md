# Support for Bean Validation
`dev.phantom.validation` · [한국어](./KOR.md)

Jakarta (and javax) Bean Validation checks for Java and Kotlin.

- **Contradictory bounds:** `@Min` above `@Max`, `@DecimalMin` above `@DecimalMax`, `@Size`/`@Length` with min above max or a negative bound.
- **Constraint on the wrong type:** `@NotBlank` on a number, `@Size` on a date, `@Past` on a `String`, `@AssertTrue` on a `String`. These fail at run time with `UnexpectedTypeException`.
- **Kotlin `@field:`:** `class SignUp(@NotBlank val name: String)` puts the constraint on the constructor parameter, so `@Valid` never checks it. The quick fix writes `@field:NotBlank`.
- **Missing `@Valid`:** a field whose type (or element type) is a project class with its own constraints (weak warning).
- **Missing `@Validated`:** a Spring service, component or repository (also through a stereotype annotation of the project's own) with constraints on method parameters but no `@Validated` (weak warning). Controllers are checked only before Spring Framework 6.1, and only for constraints on parameters: `@Valid` arguments and, since 6.1, all controller parameters are validated by Spring MVC itself.

Phantom suggests this plugin when a project depends on the Bean Validation API, Hibernate Validator or `spring-boot-starter-validation`. In other modules it does nothing.
