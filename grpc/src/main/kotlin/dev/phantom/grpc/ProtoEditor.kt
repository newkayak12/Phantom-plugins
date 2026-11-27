// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.grpc

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.phantom.grpc.ProtoTokens as T

class ProtoSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer(): Lexer = ProtoLexer()

  override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(KEYS[tokenType])

  private companion object {
    val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
      T.KEYWORD to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_KEYWORD", C.KEYWORD),
      T.STRING to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_STRING", C.STRING),
      T.INT to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_NUMBER", C.NUMBER),
      T.FLOAT to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_FLOAT", C.NUMBER),
      T.LINE_COMMENT to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_LINE_COMMENT", C.LINE_COMMENT),
      T.BLOCK_COMMENT to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_BLOCK_COMMENT", C.BLOCK_COMMENT),
      T.LBRACE to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_BRACES", C.BRACES),
      T.RBRACE to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_BRACES", C.BRACES),
      T.LPAREN to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_PARENTHESES", C.PARENTHESES),
      T.RPAREN to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_PARENTHESES", C.PARENTHESES),
      T.LBRACKET to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_BRACKETS", C.BRACKETS),
      T.RBRACKET to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_BRACKETS", C.BRACKETS),
      T.SEMI to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_SEMICOLON", C.SEMICOLON),
      T.COMMA to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_COMMA", C.COMMA),
      T.DOT to TextAttributesKey.createTextAttributesKey("PHANTOM_PROTO_DOT", C.DOT),
    )
  }
}

class ProtoSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ProtoSyntaxHighlighter()
}

class ProtoCommenter : Commenter {
  override fun getLineCommentPrefix(): String = "//"
  override fun getBlockCommentPrefix(): String = "/*"
  override fun getBlockCommentSuffix(): String = "*/"
  override fun getCommentedBlockCommentPrefix(): String? = null
  override fun getCommentedBlockCommentSuffix(): String? = null
}

class ProtoBraceMatcher : PairedBraceMatcher {
  override fun getPairs(): Array<BracePair> = arrayOf(
    BracePair(T.LBRACE, T.RBRACE, true),
    BracePair(T.LPAREN, T.RPAREN, false),
    BracePair(T.LBRACKET, T.RBRACKET, false),
  )

  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
  override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

class ProtoFindUsagesProvider : FindUsagesProvider {
  // The parser remaps declared names to NAME; the words scanner sees the plain lexer tokens.
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(ProtoLexer(), TokenSet.create(T.IDENT, T.KEYWORD), T.COMMENTS, T.STRINGS)

  override fun canFindUsagesFor(element: PsiElement): Boolean = element is ProtoTypeDefinition

  override fun getHelpId(element: PsiElement): String? = null

  override fun getType(element: PsiElement): String =
    if ((element as? ProtoNamedElement)?.node?.elementType == ProtoElements.ENUM) {
      GrpcBundle.message("proto.kind.enum")
    } else {
      GrpcBundle.message("proto.kind.message")
    }

  override fun getDescriptiveName(element: PsiElement): String =
    (element as? ProtoTypeDefinition)?.qualifiedName ?: (element as? ProtoNamedElement)?.name.orEmpty()

  override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
    if (useFullName) getDescriptiveName(element) else (element as? ProtoNamedElement)?.name.orEmpty()
}
