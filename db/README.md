# Phantom Database Console

`dev.phantom.db` · [한국어](./KOR.md)

A JDBC console for MySQL, PostgreSQL and H2.

- `.sql` files get highlighting, brace matching, comments and keyword completion.
- **Ctrl+Enter** runs the statement at the caret; the gutter has a run icon per statement.
- Results open in a sortable grid; long queries can be cancelled.
- The **Database** tool window shows data sources and a schema tree that loads one level at a time.
- Drivers come from the project's own dependencies or the local Gradle/Maven cache, so nothing is downloaded.
- **Manual commit** (a data source setting): statements run in an open transaction. The **Database Changes** tool window lists what is in it, with **Commit** and **Roll Back**; `COMMIT`/`ROLLBACK` typed in the console work too, and closing the project asks first.
- Passwords go to the IDE password safe, never to the project files.
- Nothing runs at startup; the plugin loads its code when a `.sql` file or the tool window opens.

Phantom suggests this plugin when a project depends on a MySQL, MariaDB, PostgreSQL or H2 driver.

Not yet: schema compare, migration diffs, data editing in the grid.
