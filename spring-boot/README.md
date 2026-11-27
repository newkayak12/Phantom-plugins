# Support for Spring Boot
`dev.phantom.spring.boot` · [한국어](./KOR.md)

Spring Boot support: new projects from Spring Initializr, and configuration support for `application*.yml` and `application*.properties`.

- **New project:** File → New → Project → **Spring Boot** generates the project with [Spring Initializr](https://start.spring.io) and imports it with Gradle or Maven.
  - You pick Spring Boot version, language (Java, Kotlin, Groovy), build (Gradle Kotlin/Groovy, Maven), JDK level, packaging and `.properties`/`.yaml`, then dependencies.
  - Dependencies that the chosen Spring Boot version doesn't support are marked, with the versions they need.
  - The server URL can point at your own Initializr instance.

- **Run configuration:** Run, Debug or Profile on a `@SpringBootApplication` class or its Kotlin `main` creates a **Spring Boot** configuration instead of a plain Application one, with an **Active profiles** field (passed as `-Dspring.profiles.active`). It uses the same type and options as IntelliJ IDEA, so Spring Boot configurations already in `.idea/workspace.xml` open as they are. Spring Boot applications show in the **Services** tool window.
- **Completion** for keys and values.
  - Keys come from the `spring-configuration-metadata.json` of the project's libraries.
  - They also come from the project's own `@ConfigurationProperties` classes in Java and Kotlin, which helps when the project has no configuration processor. As in Spring Boot, a class bound through a constructor (`@ConstructorBinding`, a record, a Kotlin primary constructor or a single constructor with parameters) gives its constructor parameters; other classes give their setters.
  - Metadata from the project's build output is read again when a build in the IDE finishes, and within a few seconds of a build run elsewhere.
  - Values come from metadata hints, booleans and enum constants.
- **Quick documentation** and **go to declaration** for keys.
- **Inspection:** reports deprecated keys, with a quick fix to the replacement key. Under a prefix of one of the project's own `@ConfigurationProperties` classes, it also reports keys that don't exist there.

Phantom suggests this plugin when a project depends on Spring Boot. In other projects, only the New Project entry is there.

## Ghost Mode

When Phantom's Ghost Mode is on (registry key `phantom.ghost`), this plugin's inspections and gutter icons are off. Coloring, syntax errors, completion and navigation stay.
