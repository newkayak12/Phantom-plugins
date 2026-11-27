// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.DynamicBundle
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

@NonNls
private const val BUNDLE = "messages.GrpcBundle"

object GrpcBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

object ProtoLanguage : Language("PhantomProto") {
  private fun readResolve(): Any = ProtoLanguage
  override fun getDisplayName(): String = "Protocol Buffers"
}

class ProtoFileType private constructor() : LanguageFileType(ProtoLanguage) {
  override fun getName(): String = "Phantom Protocol Buffers"
  override fun getDisplayName(): String = "Protocol Buffers"
  override fun getDescription(): String = GrpcBundle.message("proto.file.type.description")
  override fun getDefaultExtension(): String = "proto"
  override fun getIcon(): Icon = AllIcons.FileTypes.Text

  companion object {
    @JvmField
    val INSTANCE: ProtoFileType = ProtoFileType()
  }
}

class ProtoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ProtoLanguage) {
  override fun getFileType(): FileType = ProtoFileType.INSTANCE
  override fun toString(): String = "Proto file"

  val packageName: String get() = PsiTreeUtil.getChildOfType(this, ProtoPackage::class.java)?.packageName.orEmpty()
  val imports: List<ProtoImport> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ProtoImport::class.java)
  val definitions: List<ProtoNamedElement> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ProtoNamedElement::class.java)
}

class ProtoTokenType(@NonNls debugName: String) : IElementType(debugName, ProtoLanguage)
class ProtoElementType(@NonNls debugName: String) : IElementType(debugName, ProtoLanguage)

object ProtoTokens {
  @JvmField val IDENT: IElementType = ProtoTokenType("IDENT")
  @JvmField val KEYWORD: IElementType = ProtoTokenType("KEYWORD")

  /** A declared name; the parser remaps the IDENT or KEYWORD token in a declaration to it. */
  @JvmField val NAME: IElementType = ProtoTokenType("NAME")
  @JvmField val INT: IElementType = ProtoTokenType("INT")
  @JvmField val FLOAT: IElementType = ProtoTokenType("FLOAT")
  @JvmField val STRING: IElementType = ProtoTokenType("STRING")
  @JvmField val LINE_COMMENT: IElementType = ProtoTokenType("LINE_COMMENT")
  @JvmField val BLOCK_COMMENT: IElementType = ProtoTokenType("BLOCK_COMMENT")
  @JvmField val LBRACE: IElementType = ProtoTokenType("{")
  @JvmField val RBRACE: IElementType = ProtoTokenType("}")
  @JvmField val LPAREN: IElementType = ProtoTokenType("(")
  @JvmField val RPAREN: IElementType = ProtoTokenType(")")
  @JvmField val LBRACKET: IElementType = ProtoTokenType("[")
  @JvmField val RBRACKET: IElementType = ProtoTokenType("]")
  @JvmField val LT: IElementType = ProtoTokenType("<")
  @JvmField val GT: IElementType = ProtoTokenType(">")
  @JvmField val SEMI: IElementType = ProtoTokenType(";")
  @JvmField val COMMA: IElementType = ProtoTokenType(",")
  @JvmField val DOT: IElementType = ProtoTokenType(".")
  @JvmField val EQ: IElementType = ProtoTokenType("=")
  @JvmField val OTHER: IElementType = ProtoTokenType("OTHER")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
  @JvmField val STRINGS: TokenSet = TokenSet.create(STRING)
  @JvmField val WORDS: TokenSet = TokenSet.create(IDENT, KEYWORD, NAME)
  @JvmField val WHITE_SPACE: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

  val SCALAR_TYPES: Set<String> = setOf(
    "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
    "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes",
  )

  val KEYWORDS: Set<String> = SCALAR_TYPES + setOf(
    "syntax", "edition", "import", "weak", "public", "package", "option", "message", "enum", "service", "rpc",
    "returns", "stream", "oneof", "map", "repeated", "optional", "required", "reserved", "extensions", "extend",
    "to", "max", "group", "true", "false",
  )
}

object ProtoElements {
  @JvmField val SYNTAX: IElementType = ProtoElementType("SYNTAX")
  @JvmField val PACKAGE: IElementType = ProtoElementType("PACKAGE")
  @JvmField val IMPORT: IElementType = ProtoElementType("IMPORT")
  @JvmField val OPTION: IElementType = ProtoElementType("OPTION")
  @JvmField val MESSAGE: IElementType = ProtoElementType("MESSAGE")
  @JvmField val ENUM: IElementType = ProtoElementType("ENUM")
  @JvmField val ENUM_VALUE: IElementType = ProtoElementType("ENUM_VALUE")
  @JvmField val SERVICE: IElementType = ProtoElementType("SERVICE")
  @JvmField val RPC: IElementType = ProtoElementType("RPC")
  @JvmField val FIELD: IElementType = ProtoElementType("FIELD")
  @JvmField val MAP_FIELD: IElementType = ProtoElementType("MAP_FIELD")
  @JvmField val ONEOF: IElementType = ProtoElementType("ONEOF")
  @JvmField val EXTEND: IElementType = ProtoElementType("EXTEND")
  @JvmField val RESERVED: IElementType = ProtoElementType("RESERVED")
  @JvmField val TYPE_REF: IElementType = ProtoElementType("TYPE_REF")

  @JvmField val FILE: IFileElementType = IFileElementType(ProtoLanguage)
}
