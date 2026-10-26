# phantom-plugins

Plugins for [Phantom IDEA](https://github.com/newkayak12/Phantom-IDEA), kept out of the IDE so that a project only
pays for the technology it uses. [한국어](./KOR.md)

## Install

Phantom lists this repository by default. Open **Settings | Plugins | Marketplace** and search for the plugin by name.
Phantom also suggests a plugin when it finds one of the libraries the plugin lists under `detect`, or one of the files under `detectFiles`, in the project.

To add any other repository in this format: **Settings | Plugins | ⚙ | Manage Plugin Repositories**, then enter
`owner/repo`, `owner/repo@branch`, or the github.com URL.

**Private repositories** need a GitHub token with read access to the repository's contents. Phantom looks for a
token in this order and sends it only to `api.github.com` for the configured repositories:

1. The one saved with **Find Action → Set GitHub Token for Plugin Repositories** (kept in the IDE password safe)
2. The `GH_TOKEN` or `GITHUB_TOKEN` environment variable
3. `gh auth token`, if the GitHub CLI is logged in

Public repositories need no token.

## Plugins

| Plugin | Folder | Description |
|--------|--------|-------------|
| Phantom Database Console | [db](./db/README.md) · [한국어](./db/KOR.md) | JDBC console with editable drivers (MySQL, PostgreSQL, H2, AWS, Oracle and more): run SQL from the editor, result grid, schema tree |
| Phantom Spring | [spring](./spring/README.md) · [한국어](./spring/KOR.md) | Bean injection navigation, autowiring inspection, Spring MVC endpoints, Security paths and expressions, Kafka/RabbitMQ/JMS navigation (Java and Kotlin) |
| Phantom Spring Boot | [spring-boot](./spring-boot/README.md) · [한국어](./spring-boot/KOR.md) | New Spring Boot projects with Spring Initializr; `application.yml` / `.properties` key and value completion and docs |
| Phantom JPA | [jpa](./jpa/README.md) · [한국어](./jpa/KOR.md) | JPQL in `@Query`, derived query and `@Modifying` checks, entity mapping checks, repository/entity navigation |
| Phantom Reactive Streams | [reactive-streams](./reactive-streams/README.md) · [한국어](./reactive-streams/KOR.md) | Unsubscribed `Mono`/`Flux` and blocking calls inside operators (Reactor, RxJava) |
| Phantom Ktor | [ktor](./ktor/README.md) · [한국어](./ktor/KOR.md) | Ktor routes in the Endpoints tool window, including routes in `Route` extension functions |
| Phantom Kubernetes | [kubernetes](./kubernetes/README.md) · [한국어](./kubernetes/KOR.md) | Helm templates: `.Values` completion, navigation and missing-value warnings |
| Phantom Profiler | [profiler](./profiler/README.md) · [한국어](./profiler/KOR.md) | JFR profiling: Profile run action, record running JVMs, flame graph and call tree viewer |
| Phantom Bean Validation | [validation](./validation/README.md) · [한국어](./validation/KOR.md) | Contradictory bounds, constraints on the wrong type, Kotlin `@field:` targets, missing `@Valid` / `@Validated` |
| Phantom Migrations | [migrations](./migrations/README.md) · [한국어](./migrations/KOR.md) | Flyway file names and duplicate versions; Liquibase duplicate changesets and missing includes |
| Phantom OpenAPI | [openapi](./openapi/README.md) · [한국어](./openapi/KOR.md) | `$ref` navigation, completion and errors in OpenAPI/Swagger specs; spec ↔ handler gutters |
| Phantom HTTP Client | [http-client](./http-client/README.md) · [한국어](./http-client/KOR.md) | `.http` / `.rest` requests run from the editor: environments, `import` / `run`, checks, response view |
| Phantom Tab Groups | [tabs](./tabs/README.md) · [한국어](./tabs/KOR.md) | Editor tab groups as in a browser: name, color, collapse to the first tab, drag in and out, per split |
| Phantom Workspaces | [workspaces](./workspaces/README.md) · [한국어](./workspaces/KOR.md) | All open projects as tabs of one window: a project tab strip above the editor, project windows stacked at one size and position |

## Repository format

Phantom reads one file, `.phantom/marketplace.json`, from the repository's default branch (or the branch after `@`):

```json
{
  "name": "phantom-plugins",
  "owner": { "name": "newkayak12", "url": "https://github.com/newkayak12" },
  "plugins": [
    {
      "id": "dev.phantom.db",
      "name": "Phantom Database Console",
      "version": "0.1.0",
      "source": "db",
      "description": "…",
      "vendor": "Phantom",
      "category": "Database",
      "since": "262",
      "until": "262.*",
      "depends": ["com.intellij.modules.lang"],
      "download": "db-0.1.0/db-0.1.0.zip",
      "detect": ["org.postgresql:postgresql"]
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | The plugin id from its `plugin.xml` |
| `download` | yes | An `https://` URL, or `<release tag>/<asset>` from this repository's GitHub Releases |
| `name`, `version`, `description`, `vendor`, `category` | no | Shown in the plugin list; `vendor` defaults to the repository owner |
| `since`, `until` | no | Compatible Phantom builds; incompatible versions are hidden |
| `depends` | no | Plugin ids the plugin needs |
| `source` | no | The plugin's folder, linked from the plugin page |
| `detect` | no | `group:artifact` coordinates; Phantom suggests the plugin when a project uses one |
| `detectFiles` | no | File names, such as `Chart.yaml`; Phantom suggests the plugin when a project contains one |

Each plugin folder holds its sources, a `build.gradle.kts`, a `README.md` and a `KOR.md`.

## Build and release

The plugins compile against an installed Phantom IDEA (`phantomIde` in `gradle.properties`) and need JDK 25, which
Phantom bundles.

```
./gradlew :db:test :db:buildPlugin          # zip in db/build/distributions
python3 tools/release.py db                 # build, test, update marketplace.json
python3 tools/release.py db --publish       # also create the GitHub release with the zip
```

After publishing, commit and push `.phantom/marketplace.json`.

## Adding a plugin

1. Create `<folder>/build.gradle.kts` with `plugins { id("phantom-plugin") }` and a `version`.
2. Put the code in `<folder>/src/main/kotlin` and `plugin.xml` in `<folder>/src/main/resources/META-INF`.
3. Add `include("<folder>")` to `settings.gradle.kts` and an entry with `"source": "<folder>"` to `.phantom/marketplace.json`.
4. Write `<folder>/README.md` and `<folder>/KOR.md`, and add a row to the table above.

## License

Apache License 2.0.
