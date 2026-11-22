# Phantom for Ktor
`dev.phantom.ktor` · [한국어](./KOR.md)

Ktor server support.

- **Endpoints:** Ktor routes appear in Phantom's Endpoints tool window next to Spring MVC handlers, for example `GET /api/v1/menus/{id}`.
  - Routes come from the routing DSL: `get`, `post`, `put`, `delete`, `patch`, `head` and `options`, under nested `route("…") { }` blocks.
  - Routes in extension functions such as `fun Route.userRoutes()` get the prefix of every place that calls the function.
  - Paths built from constants resolve to their values.
  - Enter or double-click opens the route.

Phantom suggests this plugin when a project depends on `ktor-server-core`. In other projects, it does nothing.

Not yet: type-safe routes (`get<Users>`), the New Project wizard (start.ktor.io), `application.conf` completion.
