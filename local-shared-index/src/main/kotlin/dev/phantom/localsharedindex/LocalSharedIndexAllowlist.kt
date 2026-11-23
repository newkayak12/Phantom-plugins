// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.localsharedindex

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.flavor.FileIndexingFlavorProvider

/**
 * Which indexes may be served from data another checkout produced.
 *
 * An index is content-only when its indexer reads only the bytes, text or PSI of the one file, plus its name and
 * file type; it never calls project, module or settings services, other files, or the file's path or parent. Its
 * keys and values hold no file ids or paths, and its version covers its logic.
 */
object LocalSharedIndexAllowlist {
  const val STUBS = "Stubs"

  /**
   * Audited index ids, one reason each (sources read from the platform checkout).
   *
   * Java sources: the PSI and the lexer depend on the file's language level, which is a project setting. The platform
   * already mixes it into the indexed-file hash (`JavaFileIndexingFlavor`), so the cache key must use that hash.
   */
  val CONTENT_ONLY: Set<String> = setOf(
    // IdIndex (+ IdIndexImpl): words scanner / JavaIdIndexer lex the text only (Java with a fixed JDK_1_3 lexer, no
    // project). The libraries-vs-sources branch in JavaIdIndexer is off by default and sits behind a registry key.
    "IdIndex",
    // JavaFunctionalExpressionIndex: light AST of the one file, file-local resolution, offsets and names only.
    "java.fun.expression",
    // JavaNullMethodArgumentIndex: token list of the one file, keys are method name and argument index.
    "java.null.method.argument",
    // JavaBinaryPlusExpressionIndex: token list of the one file, values are offsets.
    "java.binary.plus.expression",
    // TrigramIndex: TrigramBuilder.getTrigramsAsMap(text), pure text function; Phantom's library-text filter runs
    // before mapping. Version includes the shard count, so keys must carry the index version.
    "Trigram.Index",
    // KotlinPackageSourcesMemberNamesIndex (simpleName): package and declaration names from the .kt PSI.
    // KotlinPartialPackageNamesIndex (canonicalName): package name of the file, split into parents.
    // KotlinTopLevelCallableByPackageShortNameIndex, KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex
    // (both simpleName): top-level declaration names by package, from the .kt PSI.
    // Kotlin ids are checked in LocalSharedIndexAllowlistKotlinTest, which needs the Kotlin plugin.
    "KotlinPackageSourcesMemberNamesIndex",
    "org.jetbrains.kotlin.idea.vfilefinder.KotlinPartialPackageNamesIndex",
    "KotlinTopLevelCallableByPackageShortNameIndex",
    "KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex",
  )

  // Left out, with the reason:
  //  TodoIndex: patterns come from the user's TODO settings (IndexPatternProvider), changing them rebuilds the index.
  //  FilenameIndex ("FilenameIndex"), FileTypeIndex ("filetypes"): do not depend on file content.
  //  JavaSourceModuleNameIndex, JavaAutoModuleNameIndex, ExternalAnnotationsIndex, BytecodeAnalysisIndex: library
  //    indexes, jars are already shared.

  fun accepts(extension: FileBasedIndexExtension<*, *>): Boolean =
    extension.name !is StubIndexKey<*, *> &&
      extension.dependsOnFileContent() &&
      extension.canBeShared() &&
      extension.name.name in CONTENT_ONLY + STUBS

  /**
   * Indexes whose files are skipped during scanning when a donor with the same content key is indexed, and queried
   * through the donor (prototype for #109; registry key [ALIAS_KEY]).
   */
  // Every audited content-only index except stubs: StubIndex keeps its own key-to-file-id maps, which a redirect of
  // the stub tree does not reach, so stubs need a platform hook (#109).
  private val ALIASED = CONTENT_ONLY
  const val ALIAS_KEY = "phantom.localsharedindex.alias"

  /** Whether the index takes part at all; [aliases] also asks the registry, at each use. */
  fun aliasable(indexName: String): Boolean = indexName in ALIASED

  fun aliases(indexName: String): Boolean = aliasable(indexName) && Registry.`is`(ALIAS_KEY, true)

  /**
   * `KotlinFileType.getName()` (`KotlinLanguage.NAME`), matched by name so that this plugin needs no Kotlin dependency.
   * The Kotlin stub builder reads the file's text and name only: `KtFile.customStubBuilder` is null for a source file
   * (its only override is `KtDecompiledFile`, for class files) and no IDE-side subclass sets it.
   */
  private const val KOTLIN_FILE_TYPE = "Kotlin"

  /**
   * Stubs are cached for file types that carry an indexing flavor (JAVA, CLASS, kotlin_builtins; see JavaPlugin.xml)
   * and for `.kt` sources. The Kotlin file type also covers `.kts` and `.gradle.kts` scripts, whose stubs may depend on
   * script definitions (project state), so those are not cached.
   */
  fun cachesStubsFor(fileType: FileType, fileName: CharSequence): Boolean =
    if (fileType.name == KOTLIN_FILE_TYPE) fileName.endsWith(".kt") else FileIndexingFlavorProvider.INSTANCE.forFileType(fileType) != null
}
