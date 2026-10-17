# Phantom Spring

`dev.phantom.spring` · [한국어](./KOR.md)

Spring support for Java and Kotlin projects.

- **Bean navigation:** a gutter icon on each injection point goes to the beans that can satisfy it, and one on each bean goes back to where it is injected. Constructor, field and Kotlin primary-constructor injection, stereotypes and meta annotations such as a custom `@UseCase`, and `@Bean` methods are all covered.
- **Autowiring inspection:** reports an injection point with no bean of its type in the project, or with several beans and nothing to pick one. Quick fixes add `@Qualifier` or `@Primary`. Optional injection, collections and test sources are skipped.
- **No false "unused":** Spring-managed classes and methods (beans, `@Bean`, event listeners and similar) are not reported as unused.
- **Endpoints tool window:** lists every Spring MVC handler by module, for example `GET /api/v1/menu/{id}  FindMenuController.findMenu`.
  - It covers `@RequestMapping` and `@GetMapping`-style shortcuts on `@Controller` classes, and on interfaces a controller implements.
  - Paths built from constants and string templates are resolved.
  - Filter by method, path or controller name. Enter or double-click opens the handler, and the context menu copies the URL.

Phantom suggests this plugin when a project depends on `spring-context` or `spring-web`. In projects without Spring, it does nothing.

Not yet: WebFlux functional routes, Spring Security, Spring Data repositories.
