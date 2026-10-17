# Phantom Spring Boot

`dev.phantom.spring.boot` · [한국어](./KOR.md)

Spring Boot support: new projects from Spring Initializr, and configuration support for `application*.yml` and `application*.properties`.

- **New project:** File → New → Project → **Spring Boot** generates the project with [Spring Initializr](https://start.spring.io) and imports it with Gradle or Maven.
  - You pick Spring Boot version, language (Java, Kotlin, Groovy), build (Gradle Kotlin/Groovy, Maven), JDK level, packaging and `.properties`/`.yaml`, then dependencies.
  - Dependencies that the chosen Spring Boot version doesn't support are marked, with the versions they need.
  - The server URL can point at your own Initializr instance.

- **Completion** for keys and values.
  - Keys come from the `spring-configuration-metadata.json` of the project's libraries.
  - They also come from the project's own `@ConfigurationProperties` classes in Java and Kotlin, which helps when the project has no configuration processor.
  - Values come from metadata hints, booleans and enum constants.
- **Quick documentation** and **go to declaration** for keys.
- **Inspection:** reports deprecated keys, with a quick fix to the replacement key. Under a prefix of one of the project's own `@ConfigurationProperties` classes, it also reports keys that don't exist there.

Phantom suggests this plugin when a project depends on Spring Boot. In other projects, only the New Project entry is there.
