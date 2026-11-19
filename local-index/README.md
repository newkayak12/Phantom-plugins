# Phantom Local Index

`dev.phantom.local-index` · [한국어](./KOR.md)

Reuses index data between checkouts of the same project on this machine, so a new worktree or clone does not index files that another checkout already indexed.

- **Local only:** data is read and written on this machine; nothing is sent anywhere.
- **Advanced Settings:** a switch in Settings | Advanced Settings | Phantom Local Index will come with the first version that reuses data (not in 0.1.0).
- **Turn off:** disable the plugin in Settings | Plugins.

## What is cached

Only indexes whose result depends on the file's own content (plus its name and file type), never on project settings or other files:

- `IdIndex`: words of the file, from text only.
- `java.fun.expression`, `java.null.method.argument`, `java.binary.plus.expression`: read the syntax tree or tokens of one Java file.
- `Trigram.Index`: trigrams of the text.
- Kotlin source indexes (`KotlinPackageSourcesMemberNamesIndex`, `KotlinPartialPackageNamesIndex`, `KotlinTopLevelCallableByPackageShortNameIndex`, `KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex`): package and declaration names from one `.kt` file.
- `Stubs`: the stub trees of Java source files, class files and Kotlin `.kt` files (the file types the platform marks with an indexing flavor, plus Kotlin sources). Kotlin `.kts` scripts are not cached: their stubs can depend on script definitions.

Not cached: `TodoIndex` (depends on your TODO patterns), `FilenameIndex` and `filetypes` (do not read content).

0.1.0 is the skeleton: the plugin registers with the platform's indexing infrastructure and passes every index through unchanged.
