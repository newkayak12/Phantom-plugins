# Support for Gradle Version Catalogs

`dev.phantom.version.catalog` · [한국어](./KOR.md)

Checks the libraries of a Gradle version catalog (`gradle/libs.versions.toml` or any `*.versions.toml`) for newer
versions on Maven Central:

- Right-click the catalog in the editor or the Project view and choose **Check for Newer Versions on Maven Central**.
- A dialog lists each library or `[versions]` entry with a newer version; the checked ones are written into the file in
  one undoable command.
- Pre-releases (alpha, beta, RC, milestone, snapshot) are offered only when the current version is one.
- A `[versions]` entry used by several libraries moves only to the newest version all of them have.

Maven Central (`repo1.maven.org`) is asked only when you run the action; nothing runs at startup or in the background.
Libraries Maven Central does not have, `[plugins]` entries (Gradle Plugin Portal), rich versions (`strictly`,
`require`) and dynamic versions (`1.+`, ranges) are skipped.

Navigation between `libs.*` accessors and catalog entries, `version.ref` resolution, rename and the unused-entry
inspection come from Phantom's Gradle support, not from this plugin.

Gradle is a trademark of Gradle, Inc.; this plugin is not affiliated with or endorsed by Gradle, Inc.
