# Support for GraphQL
`dev.phantom.graphql` · [한국어](./KOR.md)

GraphQL schemas and queries in `.graphql`, `.graphqls` and `.gql` files.

- **Editing:** highlighting, brace matching and `#` line comments. A missing `}` in a type does not break the
  definitions after it.
- **Navigation:** Ctrl+B on a type name (field types, `implements`, unions, `extend type`, fragment conditions) goes to
  its definition in any GraphQL file of the project, and on a fragment spread to the fragment. Find usages and rename
  work for types and fragments.
- **Spring GraphQL:** a gutter icon on a schema field leads to the `@QueryMapping`, `@MutationMapping`,
  `@SubscriptionMapping`, `@SchemaMapping` or `@BatchMapping` method that answers it, and one on the Java method leads
  back. Fields of `Query`, `Mutation` and `Subscription` without a handler are reported. This part does nothing when
  Spring GraphQL is not on the classpath.

Everything works offline; the plugin makes no network requests.

Not yet: running a query against an endpoint, completion, schema validation, root types renamed with `schema { ... }`,
gutter icons on Kotlin handlers. Incompatible with the JetBrains GraphQL plugin, which registers the same file type.
