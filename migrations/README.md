# Phantom Migrations

`dev.phantom.migrations` · [한국어](./KOR.md)

Flyway and Liquibase checks.

- **Flyway names:** in `db/migration` and the locations set by `spring.flyway.locations` (also from imported YAML files), names Flyway skips or rejects: `V1_init.sql` (one underscore), `v1__init.sql` (lower-case prefix), `V1__.sql` (no description).
- **Flyway duplicate versions:** versions compare as numbers, so `V1_16`, `V1.16` and `V1.016` collide. Gaps are fine and not reported.
- **Liquibase changelogs** (XML, YAML, formatted SQL): a changeset id and author used twice in one changelog, and `include` / `includeAll` paths that do not exist.

Phantom suggests this plugin when a project depends on `flyway-core` or `liquibase-core`. It works in a module that has one of them or is used by a module that has one; in other modules it does nothing.

Not yet: running migrations, a migrations view.
