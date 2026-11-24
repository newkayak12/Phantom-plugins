# Support for Testcontainers
`dev.phantom.testcontainers` · [한국어](./KOR.md)

Checks for Testcontainers tests in Java and Kotlin, each one a mistake that otherwise shows only when the tests run.

- **Container that never starts** (error): a `@Container` field in a class without `@Testcontainers` (on it, a
  superclass, an enclosing class or a meta-annotation such as `@IntegrationTest`), so nothing starts it; or a
  `@Container` whose type is not a `Startable`, such as a `Network`.
- **Container started for every test** (weak warning): an instance `@Container` field starts and stops a container
  around each test. A static one (in Kotlin, a property of the companion object) is shared by the class. Not reported
  with `@TestInstance(PER_CLASS)`. In Java, the fix makes it static.
- **Image without a version** (warning): `new PostgreSQLContainer<>("postgres")`, `DockerImageName.parse("redis:latest")`
  and the like pull whatever was pushed last. An image with a tag or a digest (`postgres:16.4`, `redis@sha256:…`) is fine.
- **Spring members that must be static** (error): a `@DynamicPropertySource` method or a `@ServiceConnection` field
  that is not static, which Spring does not find. In Kotlin, a companion object member (with `@JvmStatic` for the
  method). In Java, the fix makes it static.
