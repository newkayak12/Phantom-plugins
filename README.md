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
| Phantom MyBatis | [mybatis](./mybatis/README.md) · [한국어](./mybatis/KOR.md) | Mapper interface ↔ XML navigation, statement / resultMap / property references and errors, missing statements with a fix, `#{}` completion, SQL in mapper XML |
| Phantom Reactive Streams | [reactive-streams](./reactive-streams/README.md) · [한국어](./reactive-streams/KOR.md) | Unsubscribed `Mono`/`Flux` and blocking calls inside operators (Reactor, RxJava) |
| Phantom Ktor | [ktor](./ktor/README.md) · [한국어](./ktor/KOR.md) | Ktor routes in the Endpoints tool window, including routes in `Route` extension functions |
| Phantom Kubernetes | [kubernetes](./kubernetes/README.md) · [한국어](./kubernetes/KOR.md) | Helm templates: `.Values` completion, navigation and missing-value warnings |
| Phantom Profiler | [profiler](./profiler/README.md) · [한국어](./profiler/KOR.md) | JFR profiling: Profile run action, record running JVMs, flame graph and call tree viewer |
| Phantom Bean Validation | [validation](./validation/README.md) · [한국어](./validation/KOR.md) | Contradictory bounds, constraints on the wrong type, Kotlin `@field:` targets, missing `@Valid` / `@Validated` |
| Phantom Migrations | [migrations](./migrations/README.md) · [한국어](./migrations/KOR.md) | Flyway file names and duplicate versions; Liquibase duplicate changesets and missing includes |
| Phantom OpenAPI | [openapi](./openapi/README.md) · [한국어](./openapi/KOR.md) | `$ref` navigation, completion and errors in OpenAPI/Swagger specs; spec ↔ handler gutters |
| Phantom HTTP Client | [http-client](./http-client/README.md) · [한국어](./http-client/KOR.md) | `.http` / `.rest` requests run from the editor: environments, `import` / `run`, checks, response view |
| Phantom Icon Pack | [icons](./icons/README.md) · [한국어](./icons/KOR.md) | Material file and folder icons; Java cup and Kotlin logo on their files, Java classes by kind |
| Phantom Tab Groups | [tabs](./tabs/README.md) · [한국어](./tabs/KOR.md) | Editor tab groups as in a browser: name, color, collapse to the first tab, drag in and out, per split |
| Phantom Workspaces | [workspaces](./workspaces/README.md) · [한국어](./workspaces/KOR.md) | All open projects as tabs of one window: a project tab strip above the editor, project windows stacked at one size and position |
| Phantom AI Integration | [ai-integrations](./ai-integrations/README.md) · [한국어](./ai-integrations/KOR.md) | Claude Code and Codex next to your projects: worktree and session tree with agent terminals in one tool window, board editor tab, dashboard, usage, and remote control from another device's browser |
| Phantom AI Visualization | [ai-visualization](./ai-visualization/README.md) · [한국어](./ai-visualization/KOR.md) | The agent sessions of Phantom AI Integration as an isometric pixel-art office: a floor per project, a desk area per worktree, a person per session who types, waits, has a coffee or sleeps |
| Phantom Strings | [strings](./strings/README.md) · [한국어](./strings/KOR.md) | Case switching and conversion, escape/encode, line sort and cleanup on selections or the word at the caret (Alt+M) |
| Phantom Rainbow | [rainbow](./rainbow/README.md) · [한국어](./rainbow/KOR.md) | Brackets colored by nesting depth and indentation colored by level, in every language |
| Phantom Ignore Files | [ignore](./ignore/README.md) · [한국어](./ignore/KOR.md) | .dockerignore, .npmignore, .helmignore and more edited like .gitignore; ignore file templates |
| Phantom Bytecode Viewer | [bytecode](./bytecode/README.md) · [한국어](./bytecode/KOR.md) | ASM bytecode of a compiled Java or Kotlin class, synced with the editor's caret |
| Phantom WebP | [webp](./webp/README.md) · [한국어](./webp/KOR.md) | WebP images in the image editor, decoded in Java with no native library |
| Phantom Modal Commit | [modal-commit](./modal-commit/README.md) · [한국어](./modal-commit/KOR.md) | The modal commit dialog for Git and Mercurial instead of the Commit tool window |
| Phantom TOML | [toml](./toml/README.md) · [한국어](./toml/KOR.md) | TOML files, the base of Gradle version catalog support (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom YAML | [yaml](./yaml/README.md) · [한국어](./yaml/KOR.md) | YAML files with JSON Schema support; docker, kubernetes, openapi and migrations build on it (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Shell Script | [sh](./sh/README.md) · [한국어](./sh/KOR.md) | Shell scripts: completion, run configurations, ShellCheck and shfmt (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Lombok | [lombok](./lombok/README.md) · [한국어](./lombok/KOR.md) | Project Lombok's generated members in the editor, inspections and Delombok (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Java Bytecode Decompiler | [decompiler](./decompiler/README.md) · [한국어](./decompiler/KOR.md) | Decompiled Java source for `.class` files without sources (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Code Coverage for Java | [coverage](./coverage/README.md) · [한국어](./coverage/KOR.md) | Run with Coverage, with the IntelliJ or the JaCoCo runner (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom TextMate Bundles | [textmate](./textmate/README.md) · [한국어](./textmate/KOR.md) | Highlighting from TextMate and VS Code grammars for other file types (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Markdown | [markdown](./markdown/README.md) · [한국어](./markdown/KOR.md) | Markdown editing and preview; Shell Script, Kotlin and others build on it (based on the IntelliJ plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom GitHub | [github](./github/README.md) · [한국어](./github/KOR.md) | GitHub accounts, cloning, Share Project, pull requests, gists and Actions schemas (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom EditorConfig | [editorconfig](./editorconfig/README.md) · [한국어](./editorconfig/KOR.md) | .editorconfig applied to code style, indentation and encoding, and editing support for the files (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Copyright | [copyright](./copyright/README.md) · [한국어](./copyright/KOR.md) | Copyright notices from Velocity templates, updated on save or before commit (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom HTML Tools | [htmltools](./htmltools/README.md) · [한국어](./htmltools/KOR.md) | HTML inspections (deprecated markup, accessibility, image sizes, links) and editing actions (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom TestNG | [testng](./testng/README.md) · [한국어](./testng/KOR.md) | TestNG run configurations, test generation, references and inspections (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Java Internationalization | [java-i18n](./java-i18n/README.md) · [한국어](./java-i18n/KOR.md) | Hard-coded string inspections, I18nize and properties key references for Java (based on the IntelliJ plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom CPU Indicator | [cpu](./cpu/README.md) · [한국어](./cpu/KOR.md) | IDE and system CPU in the status bar; hover shows what the IDE spent it on (indexing, analysis, GC, JIT) |
| Phantom Log Console | [logs](./logs/README.md) · [한국어](./logs/KOR.md) | JSON logs as readable lines with stack traces, level colors, trace ids that list a request's lines |
| Phantom QueryDSL | [querydsl](./querydsl/README.md) · [한국어](./querydsl/KOR.md) | Q class paths go to the entity, Find Usages of entity fields finds QueryDSL paths, out-of-date Q classes |
| Phantom Testcontainers | [testcontainers](./testcontainers/README.md) · [한국어](./testcontainers/KOR.md) | Containers that never start or start per test, images without a version, Spring members that must be static |
| Mermaid (Phantom) | [mermaid](./mermaid/README.md) · [한국어](./mermaid/KOR.md) | Mermaid editing and preview, faster than the JetBrains copy it replaces (the IntelliJ plugin under a Phantom id) |

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
