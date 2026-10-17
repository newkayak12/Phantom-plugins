# phantom-plugins

Plugins for [Phantom IDEA](https://github.com/newkayak12/Phantom-IDEA), kept out of the IDE so that a project only
pays for the technology it uses. [한국어](./KOR.md)

## Install

Phantom lists this repository by default. Open **Settings | Plugins | Marketplace** and search for the plugin by name.
Phantom also suggests a plugin when it finds one of the libraries the plugin lists under `detect` in the project.

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
| Phantom Database Console | [db](./db/README.md) · [한국어](./db/KOR.md) | JDBC console for MySQL, PostgreSQL and H2: run SQL from the editor, result grid, schema tree |

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
