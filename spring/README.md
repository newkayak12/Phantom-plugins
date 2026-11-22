# Spring Support

`dev.phantom.spring` · [한국어](./KOR.md)

Spring support for Java and Kotlin projects.

- **Bean navigation:** a gutter icon on each injection point goes to the beans that can satisfy it, and one on each bean goes back to where it is injected. Constructor, field and Kotlin primary-constructor injection, stereotypes and meta annotations such as a custom `@UseCase`, and `@Bean` methods are all covered.
- **Autowiring inspection:** reports an injection point with no bean of its type in the project, or with several beans and nothing to pick one. Quick fixes add `@Qualifier` or `@Primary`. Optional injection, collections and test sources are skipped.
- **No false "unused":** Spring-managed classes and methods (beans, `@Bean`, event listeners and similar) are not reported as unused.
- **Endpoints:** fills Phantom's Endpoints tool window with every Spring MVC handler by module, for example `GET /api/v1/menu/{id}  FindMenuController.findMenu`.
  - It covers `@RequestMapping` and `@GetMapping`-style shortcuts on `@Controller` classes, and on interfaces a controller implements.
  - Paths built from constants and string templates are resolved.
  - Filter by method, path or controller name. Enter or double-click opens the handler, and the context menu copies the URL.
- **Spring Security:** completion of the project's handler paths (and `/**` prefixes) in `requestMatchers("…")` and the Kotlin DSL's `authorize("…")`; completion of functions, variables and `#arguments` in `@PreAuthorize` / `@PostAuthorize` / `@PreFilter` / `@PostFilter`, and an error for a `#name` that is not an argument of the method.
- **Messaging (Kafka, RabbitMQ, JMS):** gutter icons connect senders and receivers of the same topic, queue or destination, and topic names complete in the strings that name one.
  - Senders: `KafkaTemplate.send(topic, …)`, `ProducerRecord(topic, …)`, `JmsTemplate`, `RabbitTemplate.convertAndSend`.
  - Receivers: `@KafkaListener`, `@RabbitListener`, `@JmsListener`, and `subscribe(…)` on a Kafka or Confluent parallel consumer, including a topic list built in the same method or returned by a helper.
  - Topic names that only exist at runtime (`"$TOPIC-RETRY-$n"`, values from configuration) are not followed.

Phantom suggests this plugin when a project depends on `spring-context` or `spring-web`. In projects without Spring, it does nothing.

- **Spring Beans** tool window: every bean of the project by module and stereotype (controllers, services, repositories, components, configurations with their `@Bean` methods). Each bean expands into what it depends on, with the bean that fills each injection point, and where it is injected. Double-click opens the source. Computed when the window opens or on Refresh, never in the background.

Not yet: WebFlux functional routes, Spring Security filter chain views. Spring Data repositories are in [JPA Support](../jpa/README.md).

**0.9.3**: less CPU while you type in messaging code. The Kafka, RabbitMQ and JMS gutter icons look only at the line they
are on; the project-wide search runs when you click one. Results are unchanged.

**0.9.2**: the plugin uses less CPU while you type. Config and build files are read again only when they change, not on
every edit of Java or Kotlin code, and gutter icons skip non-Spring projects sooner. Results are unchanged.
