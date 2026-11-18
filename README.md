# Phantom Plugins

[![Analysis](https://github.com/newkayak12/Phantom-plugins/actions/workflows/analysis.yml/badge.svg?branch=main)](https://github.com/newkayak12/Phantom-plugins/actions/workflows/analysis.yml)
[![Strings coverage](https://codecov.io/gh/newkayak12/Phantom-plugins/branch/main/graph/badge.svg?flag=strings)](https://codecov.io/gh/newkayak12/Phantom-plugins)
[![Strings quality gate](https://sonarcloud.io/api/project_badges/measure?project=newkayak12_Phantom-plugins&metric=alert_status&token=8d7c753364a763477e920082bd02b5dafde3994a)](https://sonarcloud.io/summary/new_code?id=newkayak12_Phantom-plugins)

Plugins for [Phantom](https://github.com/newkayak12/Phantom): backend features install on demand, while
selected language and tool plugins are bundled by the IDE build. [한국어](./KOR.md)

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
| Spring Support | [spring](./spring/README.md) · [한국어](./spring/KOR.md) | Bean injection navigation, autowiring inspection, Spring MVC endpoints, Security paths and expressions, Kafka/RabbitMQ/JMS navigation (Java and Kotlin) |
| Spring Boot Support | [spring-boot](./spring-boot/README.md) · [한국어](./spring-boot/KOR.md) | New Spring Boot projects with Spring Initializr; `application.yml` / `.properties` key and value completion and docs |
| JPA Support | [jpa](./jpa/README.md) · [한국어](./jpa/KOR.md) | JPQL in `@Query`, derived query and `@Modifying` checks, entity mapping checks, repository/entity navigation |
| MyBatis Support | [mybatis](./mybatis/README.md) · [한국어](./mybatis/KOR.md) | Mapper interface ↔ XML navigation, statement / resultMap / property references and errors, missing statements with a fix, `#{}` completion, SQL in mapper XML |
| Reactive Streams Support | [reactive-streams](./reactive-streams/README.md) · [한국어](./reactive-streams/KOR.md) | Unsubscribed `Mono`/`Flux` and blocking calls inside operators (Reactor, RxJava) |
| Ktor Support | [ktor](./ktor/README.md) · [한국어](./ktor/KOR.md) | Ktor routes in the Endpoints tool window, including routes in `Route` extension functions |
| Kubernetes Support | [kubernetes](./kubernetes/README.md) · [한국어](./kubernetes/KOR.md) | Helm templates: `.Values` completion, navigation and missing-value warnings |
| Phantom Profiler | [profiler](./profiler/README.md) · [한국어](./profiler/KOR.md) | JFR profiling: Profile run action, record running JVMs, flame graph and call tree viewer |
| Bean Validation Support | [validation](./validation/README.md) · [한국어](./validation/KOR.md) | Contradictory bounds, constraints on the wrong type, Kotlin `@field:` targets, missing `@Valid` / `@Validated` |
| Phantom Migrations | [migrations](./migrations/README.md) · [한국어](./migrations/KOR.md) | Flyway file names and duplicate versions; Liquibase duplicate changesets and missing includes |
| OpenAPI Support | [openapi](./openapi/README.md) · [한국어](./openapi/KOR.md) | `$ref` navigation, completion and errors in OpenAPI/Swagger specs; spec ↔ handler gutters |
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
| WebP Support | [webp](./webp/README.md) · [한국어](./webp/KOR.md) | WebP images in the image editor, decoded in Java with no native library |
| Phantom Modal Commit | [modal-commit](./modal-commit/README.md) · [한국어](./modal-commit/KOR.md) | The modal commit dialog for Git and Mercurial instead of the Commit tool window |
| Phantom TOML | [toml](./toml/README.md) · [한국어](./toml/KOR.md) | TOML files, the base of Gradle version catalog support (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom YAML | [yaml](./yaml/README.md) · [한국어](./yaml/KOR.md) | YAML files with JSON Schema support; docker, kubernetes, openapi and migrations build on it (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Shell Script | [sh](./sh/README.md) · [한국어](./sh/KOR.md) | Shell scripts: completion, run configurations, ShellCheck and shfmt (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Lombok Support | [lombok](./lombok/README.md) · [한국어](./lombok/KOR.md) | Project Lombok's generated members in the editor, inspections and Delombok (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Bytecode Decompiler for Java | [decompiler](./decompiler/README.md) · [한국어](./decompiler/KOR.md) | Decompiled Java source for `.class` files without sources (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Phantom Code Coverage for Java | [coverage](./coverage/README.md) · [한국어](./coverage/KOR.md) | Run with Coverage, with the platform or JaCoCo runner (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| TextMate Bundles Support | [textmate](./textmate/README.md) · [한국어](./textmate/KOR.md) | Highlighting from TextMate and VS Code grammars for other file types (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| Markdown Support | [markdown](./markdown/README.md) · [한국어](./markdown/KOR.md) | Markdown editing and preview; Shell Script, Kotlin and others build on it (based on the upstream plugin; bundled with Phantom from build 262.261112, with updates from the marketplace) |
| GitHub Support | [github](./github/README.md) · [한국어](./github/KOR.md) | GitHub accounts, cloning, Share Project, pull requests, gists and Actions schemas (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| EditorConfig Support | [editorconfig](./editorconfig/README.md) · [한국어](./editorconfig/KOR.md) | .editorconfig applied to code style, indentation and encoding, and editing support for the files (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Copyright | [copyright](./copyright/README.md) · [한국어](./copyright/KOR.md) | Copyright notices from Velocity templates, updated on save or before commit (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom HTML Tools | [htmltools](./htmltools/README.md) · [한국어](./htmltools/KOR.md) | HTML inspections (deprecated markup, accessibility, image sizes, links) and editing actions (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| TestNG Support | [testng](./testng/README.md) · [한국어](./testng/KOR.md) | TestNG run configurations, test generation, references and inspections (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Internationalization for Java | [java-i18n](./java-i18n/README.md) · [한국어](./java-i18n/KOR.md) | Hard-coded string inspections, I18nize and properties key references for Java (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Terminal | [terminal](./terminal/README.md) · [한국어](./terminal/KOR.md) | The Terminal tool window with shell integration and command completion (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Natural Languages | [grazie](./grazie/README.md) · [한국어](./grazie/KOR.md) | Proofreading: grammar, spelling and style checks for text, comments and strings (based on the upstream plugin; bundled with Phantom from build 262.261113, with updates from the marketplace) |
| Phantom Properties | [properties](./properties/README.md) · [한국어](./properties/KOR.md) | Properties files: highlighting, completion, key rename, the Resource Bundle editor and inspections (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| JUnit Support | [junit](./junit/README.md) · [한국어](./junit/KOR.md) | JUnit 3–6 run configurations, gutter runs, test generation and inspections (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Phantom Repository Search | [repository-search](./repository-search/README.md) · [한국어](./repository-search/KOR.md) | Dependency completion for Maven and Gradle build files from the local index and caches (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Phantom JSON | [json](./json/README.md) · [한국어](./json/KOR.md) | JSON, JSON5 and JSON Lines editing with JSON Schema validation and completion (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Gradle DSL Support | [gradle-dsl](./gradle-dsl/README.md) · [한국어](./gradle-dsl/KOR.md) | API that reads and edits Gradle build files (Groovy, Kotlin script, version catalogs) as a model (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Groovy Scripting Support | [groovy-scripting](./groovy-scripting/README.md) · [한국어](./groovy-scripting/KOR.md) | Groovy scripting engine and the Groovy 3 library; bundled with Phantom from build 262.261116 (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Groovy Support | [groovy](./groovy/README.md) · [한국어](./groovy/KOR.md) | Groovy language support; bundled with Phantom from build 262.261116 (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Groovy Live Templates Support | [groovy-live-templates](./groovy-live-templates/README.md) · [한국어](./groovy-live-templates/KOR.md) | The groovyScript() live template macro; bundled with Phantom from build 262.261116 (based on the upstream plugin; bundled with Phantom from build 262.261116, with updates from the marketplace) |
| Phantom Images | [images](./images/README.md) · [한국어](./images/KOR.md) | Image viewer and editor (zoom, grid, chessboard, background image, scientific view) and the SVG language (based on the upstream plugin; bundled with Phantom, with updates from the marketplace) |
| Phantom CPU Indicator | [cpu](./cpu/README.md) · [한국어](./cpu/KOR.md) | IDE and system CPU in the status bar; hover shows what the IDE spent it on (indexing, analysis, GC, JIT) |
| Phantom Log Console | [logs](./logs/README.md) · [한국어](./logs/KOR.md) | JSON logs as readable lines with stack traces, level colors, trace ids that list a request's lines |
| QueryDSL Support | [querydsl](./querydsl/README.md) · [한국어](./querydsl/KOR.md) | Q class paths go to the entity, Find Usages of entity fields finds QueryDSL paths, out-of-date Q classes |
| Testcontainers Support | [testcontainers](./testcontainers/README.md) · [한국어](./testcontainers/KOR.md) | Containers that never start or start per test, images without a version, Spring members that must be static |
| Mermaid Support | [mermaid](./mermaid/README.md) · [한국어](./mermaid/KOR.md) | Mermaid editing and preview (based on the upstream plugin, built under a Phantom id) |

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

For local setup and source ownership, read [AGENTS.md](AGENTS.md). Without an installed Phantom, add `-PplatformFromRepository=true` to use the configured upstream platform; Phantom-specific runtime behavior still needs validation in Phantom. See [foundation status](.github/BASELINE.md) for the current scope and remaining checks.

The plugins compile against an installed Phantom (`phantomIde` in `gradle.properties`) and need JDK 25, which
Phantom bundles.

```
./gradlew :db:test :db:buildPlugin          # zip in db/build/distributions
python3 tools/release.py db                 # build, test, update marketplace.json
python3 tools/release.py db --publish       # also create the GitHub release with the zip
```

After publishing, commit and push `.phantom/marketplace.json`.

## Analysis CI

Repository-wide changed-file quality checks, affected-plugin tests, PR routing and labels are described in
[repository automation](.github/README.md). Claude and Codex instructions start at [AGENTS.md](AGENTS.md).

The **Plugin analysis** workflow runs on pull requests and pushes to `main`. Its initial scope is the
Phantom-owned `strings` plugin: all its unit and IDE fixture tests run on JDK 25, and JaCoCo 0.8.14
generates XML and HTML coverage. Reports are retained as workflow artifacts. These numbers do not
represent coverage of the other plugins or their inherited IntelliJ sources.

Run the same checks without an installed Phantom:

```sh
./gradlew --no-configuration-cache -PplatformFromRepository=true \
  -Porg.gradle.java.installations.paths="$JAVA_HOME" :strings:test :strings:jacocoTestReport
```

Use JDK 25; on headless Linux, run the command under `xvfb-run --auto-servernum`. The XML report is
`strings/build/reports/jacoco/test/jacocoTestReport.xml`.

Codecov receives that measured report with the `strings` flag. SonarQube Cloud analyzes only Strings
production sources and imports the same JaCoCo report; its quality gate is awaited. Test sources are
excluded from production coverage because Cloud ignores explicit test-scope settings. Configure repository
secrets `CODECOV_TOKEN` and `SONAR_TOKEN`, plus variables `SONAR_HOST_URL`, `SONAR_PROJECT_KEY` and
`SONAR_ORGANIZATION`. Disable SonarQube Cloud automatic analysis before enabling CI analysis.
Trusted runs fail when configuration or an upload fails. Fork pull requests run tests and save reports
but explicitly skip external uploads because they do not receive these credentials.

## Adding a plugin

1. Create `<folder>/build.gradle.kts` with `plugins { id("phantom-plugin") }` and a `version`.
2. Put the code in `<folder>/src/main/kotlin` and `plugin.xml` in `<folder>/src/main/resources/META-INF`.
3. Add `include("<folder>")` to `settings.gradle.kts` and an entry with `"source": "<folder>"` to `.phantom/marketplace.json`.
4. Write `<folder>/README.md` and `<folder>/KOR.md`, and add a row to the table above.

## License

Apache License 2.0.
