# Support for AsyncAPI
`dev.phantom.asyncapi` · [English](./README.md)

최상위에 `asyncapi`가 있는 YAML·JSON 파일에서만, 오프라인으로 동작합니다.

- **자동완성과 검증:** AsyncAPI 2.x와 3.x 문서에서 플러그인에 포함된 스키마로 키를 자동완성하고, 모르는 키, 빠진 필수 키,
  잘못된 값(operation의 `action`이 `send`나 `receive`가 아닌 경우)을 표시합니다. payload와 binding 내용은 검사하지
  않습니다.
- **채널 ↔ Kafka 코드:** 채널의 거터 아이콘에서 그 토픽을 쓰는 Spring Kafka 코드(`@KafkaListener` 메서드와 클래스,
  `KafkaTemplate.send` 호출)로 갑니다. 리스너나 호출의 아이콘에서는 채널로 돌아갑니다. 토픽은 채널의 Kafka binding
  `topic`이고, 없으면 채널 이름(AsyncAPI 2)이나 `address`(AsyncAPI 3)입니다. 상수 토픽만 맞추며 `${...}` 플레이스홀더와
  `topicPattern`은 풀지 않습니다. Java만 지원합니다.
- **`$ref`:** Ctrl+B로 `$ref`가 가리키는 컴포넌트, 채널, 파일로 갑니다(`#/components/messages/Order`,
  `messages/order.yaml#/Order`). 문서의 컴포넌트, 채널, 서버, operation을 자동완성하고, 대상 이름을 바꾸면 포인터도
  바뀌며, 가리키는 대상이 없는 `$ref`는 오류로 표시합니다. 원격 `http(s)` 참조는 검사하지도 내려받지도 않습니다.

코드 생성이나 브로커 접속은 하지 않습니다.
