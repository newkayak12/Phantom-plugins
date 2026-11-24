# Support for JPA
`dev.phantom.jpa` · [English](./README.md)

Java와 Kotlin을 위한 JPA, Hibernate, Spring Data JPA 지원입니다.

- **JPQL:** `@Query`와 `@NamedQuery` 안의 쿼리를 다룹니다. `const val`이나 `static final` 상수, Kotlin 문자열 템플릿에 둔 쿼리도 포함합니다. 하이라이팅, 엔티티·속성 자동완성, 이동을 지원하고, 찾을 수 없는 이름은 오류로 표시합니다.
- **파생 쿼리 메서드**(`findByNameAndCreatedAtAfter`): 속성 경로를 자동완성하고, 엔티티에 없는 속성이나 모자란 파라미터를 오류로 표시합니다.
- **`@Modifying`:** `UPDATE`, `DELETE`, `INSERT` 쿼리에 `@Modifying`이 없으면 오류로 표시하고, 빠른 수정으로 붙여 줍니다.
- **엔티티 매핑:** 자신이나 상위 클래스에 `@Id`/`@EmbeddedId`가 없는 `@Entity`, 상대 엔티티에 없는 속성을 가리키는 `mappedBy`를 알려 줍니다.
- **거터 이동:** 리포지토리에서 엔티티로, 엔티티에서 리포지토리로 이동합니다.

프로젝트가 Spring Data JPA, Hibernate, Jakarta Persistence API에 의존하면 Phantom이 이 플러그인 설치를 제안합니다. 다른 프로젝트에서는 아무 동작도 하지 않습니다.

아직 없는 것: HQL 전용 문법, Persistence 툴 윈도우, ER 다이어그램.
