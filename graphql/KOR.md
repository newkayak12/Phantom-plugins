# Support for GraphQL
`dev.phantom.graphql` · [English](./README.md)

`.graphql`, `.graphqls`, `.gql` 파일의 GraphQL 스키마와 쿼리.

- **편집:** 하이라이팅, 괄호 짝 맞추기, `#` 줄 주석. 타입에서 `}`가 빠져도 뒤의 정의가 깨지지 않는다.
- **이동:** 타입 이름(필드 타입, `implements`, union, `extend type`, 프래그먼트 조건)에서 Ctrl+B를 누르면 프로젝트의
  어느 GraphQL 파일에 있든 그 정의로, 프래그먼트 스프레드에서는 프래그먼트로 간다. 타입과 프래그먼트는 사용처 찾기와
  이름 바꾸기도 된다.
- **Spring GraphQL:** 스키마 필드의 거터 아이콘은 그 필드에 답하는 `@QueryMapping`, `@MutationMapping`,
  `@SubscriptionMapping`, `@SchemaMapping`, `@BatchMapping` 메서드로, Java 메서드의 아이콘은 스키마 필드로 간다.
  핸들러가 없는 `Query`, `Mutation`, `Subscription` 필드는 경고로 표시된다. 클래스패스에 Spring GraphQL이 없으면
  이 부분은 아무 일도 하지 않는다.

모두 오프라인으로 동작하며 네트워크 요청을 하지 않는다.

아직 없음: 엔드포인트에 쿼리 실행, 자동 완성, 스키마 검증, `schema { ... }`로 이름을 바꾼 루트 타입, Kotlin 핸들러의 거터
아이콘. 같은 파일 형식을 등록하는 JetBrains GraphQL 플러그인과 함께 쓸 수 없다.
