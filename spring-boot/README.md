# Phantom Spring Boot

`dev.phantom.spring.boot` · [한국어](./KOR.md)

Spring Boot configuration support for `application*.yml` and `application*.properties`.

- **Completion** for keys and values.
  - Keys come from the `spring-configuration-metadata.json` of the project's libraries.
  - They also come from the project's own `@ConfigurationProperties` classes in Java and Kotlin, which helps when the project has no configuration processor.
  - Values come from metadata hints, booleans and enum constants.
- **Quick documentation** and **go to declaration** for keys.
- **Inspection:** reports deprecated keys, with a quick fix to the replacement key. Under a prefix of one of the project's own `@ConfigurationProperties` classes, it also reports keys that don't exist there.

Phantom suggests this plugin when a project depends on Spring Boot. In other projects, it does nothing.
