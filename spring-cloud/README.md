# Support for Spring Cloud
`dev.phantom.spring.cloud` · [한국어](./KOR.md)

Spring Cloud support that works offline, in projects that use it.

- **Feign client ↔ controller:** a gutter icon on a `@FeignClient` interface method goes to the controller method of the
  same project that serves its request; one on the controller method goes back to the Feign client methods calling it.
  The request is the HTTP method (`@GetMapping`, `@PostMapping`, ..., `@RequestMapping(method = ...)`) and the path:
  the client's `path` attribute and the method mapping, against the controller's `@RequestMapping` and method mapping.
  Path variables match by position (`{id}` and `{userId:\d+}`), constants are evaluated, and mappings declared on
  an interface the controller implements count.

Java only for now. The client's `name` and `url` are not compared, so two services with the same path in one project
both match. No running service registry is contacted.
