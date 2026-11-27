# Phantom for Spring Security
`dev.phantom.spring.security` · [한국어](./KOR.md)

Spring Security filter chains in Java, read from the source without running the application.

- **Filter chain gutter:** each `@Bean` method returning `SecurityFilterChain` gets a lock icon. Its tooltip shows the chain's place among the project's chains (by `@Order`, unordered chains last), its `securityMatcher` paths or "every request", and the `authorizeHttpRequests` rules in order, such as `POST /admin/** → hasRole("ADMIN")`.
- **Request matcher after `anyRequest()`:** an error on `requestMatchers(...)` written after `anyRequest()`. Spring Security rejects this at startup.
- **Shadowed filter chain:** an error on a chain ordered after a chain without `securityMatcher`. That chain handles every request, and Spring Security rejects the later ones at startup. Chains with the same order are not reported, because their order depends on bean registration.

The chains are read from `securityMatcher(...)` and the lambda passed to `authorizeHttpRequests` inside the bean method. Builders assembled in other methods and the Kotlin DSL are not read yet.

Completion of paths in `requestMatchers` and of `@PreAuthorize` expressions is in [Support for Spring](../spring/README.md).

Phantom suggests this plugin when a project depends on `spring-security-web` or `spring-boot-starter-security`. In modules without Spring Security it does nothing. It makes no network requests and registers no startup work.
