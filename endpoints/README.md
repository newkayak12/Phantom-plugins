# Phantom Endpoints

`dev.phantom.endpoints` · [한국어](./KOR.md)

The Endpoints tool window: every HTTP endpoint that installed plugins report through `com.intellij.microservices.endpointsProvider`
(Spring Support, Ktor Support, …), grouped by module, with a filter by method, path or handler.

- Enter or double-click opens the handler; the context menu has Go to Handler and Copy URL.
- Generate HTTP Request appends a request for the endpoint to the scratch file `generated-requests.http` and opens it there.
  Path parameters become `{{variables}}`; the host comes from the module's `server.port`, `server.servlet.context-path`,
  `spring.webflux.base-path`, `server.ssl.enabled` or Ktor's `ktor.deployment.port`, else `http://localhost:8080`.
- Lists are built in the background only while the window is visible and rebuilt after code changes. Projects without an
  endpoints provider don't get the window.

Phantom bundles this plugin (it used to be part of the IDE itself), with updates from the marketplace.
