# Phantom Local Index

`dev.phantom.local-index` · [한국어](./KOR.md)

Reuses index data between checkouts of the same project on this machine, so a new worktree or clone does not index files that another checkout already indexed.

- **Local only:** data is read and written on this machine; nothing is sent anywhere.
- **Advanced Settings:** Settings | Advanced Settings | Phantom Local Index | Cache size limit (MB), default 2048. When the cache grows past it, the oldest data is deleted. The new limit applies after restarting the IDE.
- **Turn off:** disable the plugin in Settings | Plugins.

## What is cached

Only indexes whose result depends on the file's own content (plus its name and file type), never on project settings or other files:

- `IdIndex`: words of the file, from text only.
- `java.fun.expression`, `java.null.method.argument`, `java.binary.plus.expression`: read the syntax tree or tokens of one Java file.
- `Trigram.Index`: trigrams of the text.
- Kotlin source indexes (`KotlinPackageSourcesMemberNamesIndex`, `KotlinPartialPackageNamesIndex`, `KotlinTopLevelCallableByPackageShortNameIndex`, `KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex`): package and declaration names from one `.kt` file.
- `Stubs`: the stub trees of Java source files, class files and Kotlin `.kt` files (the file types the platform marks with an indexing flavor, plus Kotlin sources). Kotlin `.kts` scripts are not cached: their stubs can depend on script definitions.

Not cached: `TodoIndex` (depends on your TODO patterns), `FilenameIndex` and `filetypes` (do not read content).

## Lifecycle

- One IDE process owns the cache at a time. If another IDE process already holds it (or it cannot be opened), this process runs without the cache and logs a warning.
- A damaged cache needs no manual cleanup: unreadable entries count as misses and are removed, and a cache written by another plugin version, index version or index layout is cleared on start.
- When the platform rebuilds or resets an index, the cached data of that index is cleared too.

