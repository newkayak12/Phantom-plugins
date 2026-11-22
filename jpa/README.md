# Phantom for JPA
`dev.phantom.jpa` · [한국어](./KOR.md)

JPA, Hibernate and Spring Data JPA support for Java and Kotlin.

- **JPQL** inside `@Query` and `@NamedQuery`, including queries kept in `const val` / `static final` constants and Kotlin templates: highlighting, completion of entities and attributes, navigation, and errors for names that don't resolve.
- **Derived query methods** (`findByNameAndCreatedAtAfter`): completion of property paths, and an error for a property the entity doesn't have or for missing parameters.
- **`@Modifying`:** an `UPDATE`, `DELETE` or `INSERT` `@Query` without `@Modifying` is an error, with a quick fix that adds it.
- **Entity mapping:** an `@Entity` without `@Id`/`@EmbeddedId` on itself or a superclass, and a `mappedBy` naming an attribute the other entity doesn't have.
- **Gutter navigation** from a repository to its entity, and from an entity to its repositories.

Phantom suggests this plugin when a project depends on Spring Data JPA, Hibernate or the Jakarta Persistence API. In other projects, it does nothing.

Not yet: HQL-only syntax, the Persistence tool window, ER diagrams.
