# Support for Testcontainers
`dev.phantom.testcontainers` · [English](./README.md)

Java·Kotlin의 Testcontainers 테스트를 검사합니다. 모두 테스트를 돌려 봐야 드러나는 실수입니다.

- **시작되지 않는 컨테이너**(오류): `@Testcontainers`가 없는 클래스의 `@Container` 필드는 아무도 시작하지 않습니다.
  클래스 자신, 상위 클래스, 바깥 클래스, `@IntegrationTest` 같은 메타 애너테이션 중 어디에든 있으면 됩니다.
  `Network`처럼 `Startable`이 아닌 타입에 붙은 `@Container`도 알려 줍니다. 하위 클래스가 `@Testcontainers`를 다는
  추상 기반 클래스에서는 알리지 않습니다.
- **테스트마다 새로 시작하는 컨테이너**(약한 경고): 인스턴스 `@Container` 필드는 테스트마다 컨테이너를 띄우고 내립니다.
  static(Kotlin은 companion object의 프로퍼티)이면 클래스 전체가 하나를 씁니다. `@TestInstance(PER_CLASS)`면
  알리지 않습니다. Java에서는 빠른 수정으로 static으로 바꿉니다.
- **버전 없는 이미지**(경고): `new PostgreSQLContainer<>("postgres")`, `DockerImageName.parse("redis:latest")` 같은 이미지는
  마지막에 올라온 것을 받습니다. 태그나 digest가 있으면(`postgres:16.4`, `redis@sha256:…`) 괜찮고,
  `DockerImageName.parse("postgres").withTag("16")`도 괜찮습니다.
- **static이어야 하는 Spring 멤버**(오류): static이 아닌 `@DynamicPropertySource` 메서드와 `@ServiceConnection` 필드는
  Spring이 찾지 못합니다. Kotlin에서는 companion object 멤버여야 하고, 메서드에는 `@JvmStatic`이 필요합니다.
  Java에서는 빠른 수정으로 static으로 바꿉니다.
