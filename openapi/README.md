# Phantom for OpenAPI
`dev.phantom.openapi` · [한국어](./KOR.md)

OpenAPI 3 and Swagger 2 specifications in YAML and JSON.

- **`$ref`:** Ctrl+B to `#/components/...` entries and to other files (`common.yaml#/components/schemas/Error`), completion of `#/components/<kind>/<name>`, and an error for a `$ref` that points to nothing. Remote references are not checked.
- **Spec ↔ code:** a gutter icon on each operation (`get:` under a path) leads to the handler, and one on the handler leads back to the spec. Handlers come from Phantom's Endpoints view (Spring MVC and other frameworks it knows). Paths match with parameter names ignored and a server base path allowed.

Only files whose top level has `openapi:` or `swagger:` are treated as specs.

Not yet: schema validation of the spec itself, code generation.
