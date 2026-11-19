// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

@NonNls
private const val BUNDLE = "messages.PhantomHttpBundle"

object PhantomHttpBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

object HttpRequestLanguage : Language("PhantomHttpRequest") {
  private fun readResolve(): Any = HttpRequestLanguage
  override fun getDisplayName(): String = "HTTP Request"
}

class HttpRequestFileType private constructor() : LanguageFileType(HttpRequestLanguage) {
  override fun getName(): String = "Phantom HTTP Request"
  override fun getDisplayName(): String = "HTTP Request"
  override fun getDescription(): String = PhantomHttpBundle.message("http.file.type.description")
  override fun getDefaultExtension(): String = "http"
  override fun getIcon(): Icon = AllIcons.FileTypes.Text

  companion object {
    @JvmField
    val INSTANCE: HttpRequestFileType = HttpRequestFileType()
  }
}

class HttpRequestFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HttpRequestLanguage) {
  override fun getFileType(): FileType = HttpRequestFileType.INSTANCE
  override fun toString(): String = "HTTP request file"

  val blocks: List<HttpRequestBlock> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HttpRequestBlock::class.java)
  val requests: List<HttpRequestBlock> get() = blocks.filter { it.requestLine != null }
  val imports: List<HttpImport> get() = PsiTreeUtil.findChildrenOfType(this, HttpImport::class.java).toList()
}

class HttpTokenType(@NonNls debugName: String) : IElementType(debugName, HttpRequestLanguage)
class HttpElementType(@NonNls debugName: String) : IElementType(debugName, HttpRequestLanguage)

object HttpTokens {
  /** `###` and the request name after it. */
  @JvmField val SEPARATOR: IElementType = HttpTokenType("SEPARATOR")
  @JvmField val REQUEST_NAME: IElementType = HttpTokenType("REQUEST_NAME")
  @JvmField val COMMENT: IElementType = HttpTokenType("COMMENT")
  /** `# @name login`, `# @no-redirect`, `# @timeout 5`: the whole line. */
  @JvmField val DIRECTIVE: IElementType = HttpTokenType("DIRECTIVE")
  /** `@host` in `@host = http://localhost:8080`. */
  @JvmField val FILE_VARIABLE_NAME: IElementType = HttpTokenType("FILE_VARIABLE_NAME")
  @JvmField val EQ: IElementType = HttpTokenType("=")
  @JvmField val VALUE_TEXT: IElementType = HttpTokenType("VALUE_TEXT")
  @JvmField val METHOD: IElementType = HttpTokenType("METHOD")
  @JvmField val URL_TEXT: IElementType = HttpTokenType("URL_TEXT")
  @JvmField val HTTP_VERSION: IElementType = HttpTokenType("HTTP_VERSION")
  @JvmField val HEADER_NAME: IElementType = HttpTokenType("HEADER_NAME")
  @JvmField val COLON: IElementType = HttpTokenType(":")
  @JvmField val HEADER_VALUE: IElementType = HttpTokenType("HEADER_VALUE")
  @JvmField val BODY_TEXT: IElementType = HttpTokenType("BODY_TEXT")
  /** `< ./body.json`: the whole line. */
  @JvmField val INPUT_FILE: IElementType = HttpTokenType("INPUT_FILE")
  @JvmField val HANDLER_START: IElementType = HttpTokenType("HANDLER_START")
  @JvmField val SCRIPT: IElementType = HttpTokenType("SCRIPT")
  @JvmField val HANDLER_END: IElementType = HttpTokenType("HANDLER_END")
  /** `> ./handler.js`: the whole line. */
  @JvmField val HANDLER_FILE: IElementType = HttpTokenType("HANDLER_FILE")
  @JvmField val LBRACES: IElementType = HttpTokenType("{{")
  @JvmField val VARIABLE_NAME: IElementType = HttpTokenType("VARIABLE_NAME")
  @JvmField val RBRACES: IElementType = HttpTokenType("}}")

  /** `import ./other.http`: the keyword and the path. */
  @JvmField val IMPORT_KEYWORD: IElementType = HttpTokenType("IMPORT_KEYWORD")
  @JvmField val IMPORT_PATH: IElementType = HttpTokenType("IMPORT_PATH")
  /** `run #Login (@user=admin)`, `run ./other.http`: the keyword, `#name` or a path, then `(@var=value, ...)`. */
  @JvmField val RUN_KEYWORD: IElementType = HttpTokenType("RUN_KEYWORD")
  @JvmField val RUN_REQUEST_NAME: IElementType = HttpTokenType("RUN_REQUEST_NAME")
  @JvmField val RUN_PATH: IElementType = HttpTokenType("RUN_PATH")
  @JvmField val RUN_VARIABLES: IElementType = HttpTokenType("RUN_VARIABLES")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(COMMENT)

  val METHODS: Set<String> = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")
}

object HttpElements {
  @JvmField val REQUEST_BLOCK: IElementType = HttpElementType("REQUEST_BLOCK")
  @JvmField val REQUEST_LINE: IElementType = HttpElementType("REQUEST_LINE")
  @JvmField val REQUEST_TARGET: IElementType = HttpElementType("REQUEST_TARGET")
  @JvmField val HEADER: IElementType = HttpElementType("HEADER")
  @JvmField val BODY: IElementType = HttpElementType("BODY")
  @JvmField val HANDLER: IElementType = HttpElementType("HANDLER")
  @JvmField val DIRECTIVE: IElementType = HttpElementType("DIRECTIVE")
  @JvmField val FILE_VARIABLE: IElementType = HttpElementType("FILE_VARIABLE")
  @JvmField val VARIABLE_REF: IElementType = HttpElementType("VARIABLE_REF")
  @JvmField val IMPORT: IElementType = HttpElementType("IMPORT")
  @JvmField val RUN: IElementType = HttpElementType("RUN")

  @JvmField val FILE: IFileElementType = IFileElementType(HttpRequestLanguage)
}
