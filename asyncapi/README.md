# Phantom for AsyncAPI
`dev.phantom.asyncapi` · [한국어](./KOR.md)

AsyncAPI support that works offline, only in YAML and JSON files whose top level declares `asyncapi`.

- **Completion and validation:** AsyncAPI 2.x and 3.x documents get keys completed and unknown keys, missing required
  keys and wrong values (an operation `action` other than `send` or `receive`) reported, from schemas bundled with
  the plugin. Payloads and bindings are not checked.
- **Channels ↔ Kafka code:** a gutter icon on a channel goes to the Spring Kafka code of its topic: `@KafkaListener`
  methods and classes, and `KafkaTemplate.send` calls. One on the listener or the call goes back to the channels. The
  topic is the channel's Kafka binding `topic`, else the channel name (AsyncAPI 2) or its `address` (AsyncAPI 3).
  Only constant topics count; `${...}` placeholders and `topicPattern` are not resolved. Java only.
- **`$ref`:** Ctrl+B goes to the component, channel or file a `$ref` points to (`#/components/messages/Order`,
  `messages/order.yaml#/Order`), completion offers the document's components, channels, servers and operations,
  renaming the target updates the pointer, and a `$ref` that resolves to nothing is an error. Remote `http(s)`
  references are neither checked nor downloaded.

No code generation and no connection to a broker.
