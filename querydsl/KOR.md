# Support for QueryDSL
`dev.phantom.querydsl` · [English](./README.md)

QueryDSL이 생성한 Q클래스에서 원래 엔티티로 이어 줍니다.

- **Go to Declaration:** Q클래스 경로에서 생성된 코드 대신 엔티티로 갑니다. `order.customer.name`은 `Customer.name`으로,
  `order.id`는 매핑된 상위 클래스의 `id`로, `QOrder.order`는 `Order`로 갑니다. Java와 Kotlin 코드 모두 됩니다.
- **Find Usages:** Java 엔티티 필드에서 Find Usages를 하면 QueryDSL 경로(`QOrder.order.name`)도 찾습니다. 매핑된 상위
  클래스의 필드는 하위 클래스들의 Q클래스를 거친 사용처까지 찾습니다. 엔티티에서 하면 `QOrder.order`도 찾습니다.
- **Q클래스가 오래됨:** Q클래스가 생성된 뒤 바뀐 Java·Kotlin 엔티티를 경고합니다. Q클래스에 경로가 없는 필드(그 뒤 추가됨)나,
  사라진 필드의 경로가 Q클래스에 남아 있는 경우입니다. 빌드하면 다시 생성됩니다. static, `transient`, `@Transient`,
  `@QueryTransient` 필드는 경로가 없으므로 경고하지 않습니다.

Q클래스는 `Q…` 이름에 QueryDSL 경로(`EntityPathBase`, `BeanPath`)를 상속한 클래스이고, 그 경로의 타입 인자가 엔티티입니다.
중첩 클래스 `Outer.Inner`의 Q클래스는 `QOuter_Inner`입니다. 오래됨 검사는 QueryDSL이 있는 모듈의 엔티티, 임베더블, 매핑된
상위 클래스, `@QueryEntity` 클래스만 보고, `isActive()`로 읽는 boolean 속성의 경로는 남아 있는 것으로 봅니다.
Find Usages는 엔티티가 Java일 때만 됩니다.

## 고스트 모드

Phantom의 고스트 모드(레지스트리 키 `phantom.ghost`)가 켜져 있으면 Q클래스가 오래됨 검사가 꺼진다. Q클래스 경로에서 엔티티로 이동과 사용처 찾기는 그대로다.
