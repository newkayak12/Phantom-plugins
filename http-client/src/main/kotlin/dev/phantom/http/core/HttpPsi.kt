// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil

open class HttpElement(node: ASTNode) : ASTWrapperPsiElement(node)

/** Everything from one `###` to the next. A block without a request line holds only variables or comments. */
class HttpRequestBlock(node: ASTNode) : HttpElement(node) {
  val requestLine: HttpRequestLine? get() = findChildByClass(HttpRequestLine::class.java)
  val headers: List<HttpHeader> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HttpHeader::class.java)
  val body: HttpBody? get() = findChildByClass(HttpBody::class.java)
  /** Handlers after the request line; one written before it belongs to no request. */
  val handlers: List<HttpHandler> get() {
    val line = requestLine ?: return emptyList()
    return PsiTreeUtil.getChildrenOfTypeAsList(this, HttpHandler::class.java).filter { it.startOffsetInParent > line.startOffsetInParent }
  }
  val directives: List<HttpDirective> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HttpDirective::class.java)
  /** `run #name` / `run ./file.http` lines of this block. */
  val runs: List<HttpRun> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HttpRun::class.java)

  /** `### Login` or `# @name login`. */
  val requestName: String?
    get() = directives.firstOrNull { it.directiveName == "name" }?.argument?.takeIf { it.isNotEmpty() }
            ?: node.findChildByType(HttpTokens.REQUEST_NAME)?.text?.trim()?.takeIf { it.isNotEmpty() }

  fun directive(name: String): HttpDirective? = directives.firstOrNull { it.directiveName == name }

  /** What the run gutter and the tool window tab show. */
  val presentableName: String
    get() = requestName ?: requestLine?.let { "${it.method} ${it.target?.text.orEmpty()}" } ?: "request"
}

class HttpRequestLine(node: ASTNode) : HttpElement(node) {
  val method: String get() = node.findChildByType(HttpTokens.METHOD)?.text ?: "GET"
  val target: HttpRequestTarget? get() = findChildByClass(HttpRequestTarget::class.java)
  val httpVersion: String? get() = node.findChildByType(HttpTokens.HTTP_VERSION)?.text
}

/** The URL, including continuation lines (`    ?page=0`, `    &size=20`). */
class HttpRequestTarget(node: ASTNode) : HttpElement(node)

class HttpHeader(node: ASTNode) : HttpElement(node) {
  val headerName: String get() = node.findChildByType(HttpTokens.HEADER_NAME)?.text?.trim().orEmpty() }

class HttpBody(node: ASTNode) : HttpElement(node)

class HttpHandler(node: ASTNode) : HttpElement(node)

class HttpDirective(node: ASTNode) : HttpElement(node) {
  /** `name` for `# @name login`. */
  val directiveName: String get() = text.substringAfter('@').substringBefore(' ').substringBefore('\t').trim()
  val argument: String get() = text.substringAfter('@').trim().substringAfter(directiveName, "").trim()
}

/** `@host = http://localhost:8080`. */
class HttpFileVariable(node: ASTNode) : HttpElement(node), PsiNamedElement {
  override fun getName(): String = node.firstChildNode.text.removePrefix("@")
  override fun setName(name: String): PsiElement {
    val created = PsiTreeUtil.findChildOfType(httpFragment(project, "@$name = x"), HttpFileVariable::class.java) ?: return this
    node.replaceChild(node.firstChildNode, created.node.firstChildNode)
    return this
  }
  override fun getTextOffset(): Int = textRange.startOffset

  /** The raw value text, still with `{{...}}` references. */
  val rawValue: String
    get() {
      val eq = node.findChildByType(HttpTokens.EQ) ?: return ""
      val start = eq.textRange.endOffset - textRange.startOffset
      return text.substring(start).trim()
    }
}

/** `{{name}}`. */
class HttpVariableRef(node: ASTNode) : HttpElement(node) {
  val variableName: String get() = node.findChildByType(HttpTokens.VARIABLE_NAME)?.text?.trim().orEmpty()

  private val nameRange: TextRange
    get() {
      val nameNode = node.findChildByType(HttpTokens.VARIABLE_NAME) ?: return TextRange(2, 2)
      return TextRange.from(nameNode.startOffsetInParent, nameNode.textLength)
    }

  override fun getReference(): PsiReference = VariableReference(this, nameRange)

  private class VariableReference(element: HttpVariableRef, range: TextRange) : PsiReferenceBase<HttpVariableRef>(element, range, true) {
    override fun resolve(): PsiElement? = HttpVariables.findDeclaration(element)

    override fun getVariants(): Array<Any> = HttpVariables.candidates(element).map { (name, origin) ->
      LookupElementBuilder.create(name).withTypeText(origin, true)
    }.toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
      val old = element.node.findChildByType(HttpTokens.VARIABLE_NAME) ?: return element
      val created = PsiTreeUtil.findChildOfType(httpFragment(element.project, "GET {{$newElementName}}"), HttpVariableRef::class.java)
        ?.node?.findChildByType(HttpTokens.VARIABLE_NAME) ?: return element
      element.node.replaceChild(old, created)
      return element
    }
  }
}

/** A throwaway `.http` file whose tokens rename copies into the real one. */
private fun httpFragment(project: Project, text: String): PsiFile =
  PsiFileFactory.getInstance(project).createFileFromText("fragment.http", HttpRequestFileType.INSTANCE, text)
