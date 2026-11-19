# Phantom Local Shared Index

`dev.phantom.local-shared-index` · [한국어](./KOR.md)

Reuses index data between checkouts of the same project on this machine, so a new worktree or clone does not index files that another checkout already indexed.

- **Local only:** data is read and written on this machine; nothing is sent anywhere.
- **Advanced Settings:** Settings | Advanced Settings | Phantom Local Shared Index | Cache size limit (MB), default 2048. When the cache grows past it, the oldest data is deleted. The new limit applies after restarting the IDE.
- **Turn off:** disable the plugin in Settings | Plugins.
- **Not bundled:** Phantom does not ship this plugin; install it if you open several checkouts of the same project.

## What to expect

Measured on a Kotlin/Gradle project of about 2,600 source files, opening a new worktree, one IDE restart per run:

- Files already indexed by another checkout: the content indexing phase is 1.3 to 1.6 times shorter (about 1 s saved). The rest of the time goes to the platform writing the data into its own index storage, which this plugin does not skip.
- A project with nothing in the cache: content indexing may take about 0.5 s longer (it is computed and also stored). The difference was within run-to-run noise (about 1.2 s).

## What is cached

Only indexes whose result depends on the file's own content (plus its name and file type), never on other files or on a project setting the platform does not already put into the file's hash (the Java language level is such a setting, so it is part of the cache key):

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

## Diagnostics

- After each project indexing the IDE log (`idea.log`) gets one line, for example `Phantom local shared index: demo: 190 of 200 index updates from cache (IdIndex 100/100, Stubs 90/100), about 2000 ms of indexing saved`. "Saved" is the time it took to compute the reused data the first time.
- Registry key `phantom.localsharedindex.verify.one.in` (default 0 = off): with N > 0, every Nth cache hit is also recomputed and compared. A difference is logged as an error, and the cache stays off for that index until restart.
