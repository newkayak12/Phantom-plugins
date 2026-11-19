package com.phantom.mermaid.jcef

import com.phantom.mermaid.api.Mermaid
import com.phantom.mermaid.api.MermaidRenderResult
import com.phantom.mermaid.api.appendTo
import kotlinx.coroutines.await
import org.w3c.dom.Element
import org.w3c.dom.asList

/** A JavaScript WeakMap: an entry goes with its element. */
external class WeakMap<K : Any, V> {
  fun get(key: K): V?
  fun set(key: K, value: V): WeakMap<K, V>
}

// Phantom: weak, so the blocks Incremental DOM replaces do not stay in memory with their last SVG
val nodeToLastValidHtml = WeakMap<Element, String>()

private fun handleFailedRender(block: Element, exception: Throwable) {
  val lastValidRenderResult = nodeToLastValidHtml.get(block) ?: ""
  block.innerHTML = lastValidRenderResult

  // language=HTML
  val html = """<div class="error-text">${exception.message}</div>$lastValidRenderResult"""
  block.innerHTML = html

  block.findSvgElement()?.setAttribute("opacity", "50%")
}

suspend fun renderBlock(
  block: Element,
  cacheId: String,
  content: String
): MermaidRenderResult? {
  if (content.isBlank() && nodeToLastValidHtml.get(block) == null) return null

  val id = "mermaid-generated-$cacheId"
  try {
    // Remove when `mermaid.render` will throw correct error messages
    Mermaid.core.parse(content).await()

    val renderResult = Mermaid.core.render(id, content).await()
    renderResult.appendTo(block)
    val node = block.findSvgElement()
    checkNotNull(node) { "Failed to find svg node after append" }

    node.updatePieDiagramViewBox()
    node.convertExplicitHeightAndWidthAttributesToStyle()

    nodeToLastValidHtml.set(block, block.innerHTML)
    renderResult.svg = block.innerHTML
    return renderResult
  } catch (exception: Throwable) {
    console.error("Error while generating blocks:\n", exception)
    handleFailedRender(block, exception)
  }
  return null
}

private fun Element.findSvgElement(): Element? {
  return findChildElement { it.nodeName == "svg" }
}

private fun Element.convertExplicitHeightAndWidthAttributesToStyle() {
  removeAttribute("height")
  if (hasAttribute("style")) return

  val width = getAttribute("width")?.toDoubleOrNull() ?: return
  setAttribute("width", "100%")

  setAttribute("style", "max-width: ${width}px;")
}

private fun Element.updatePieDiagramViewBox() {
  if (getAttribute("aria-roledescription") != "pie") return

  val childElement = findChildElement { it.nodeName == "g" && it.hasAttribute("transform") } ?: return

  val height = getAttribute("viewBox")?.split(" ")?.lastOrNull()
  removeAttribute("viewBox")
  val rect = childElement.getBoundingClientRect()
  setAttribute("viewBox", "0 0 ${rect.right} ${height ?: rect.bottom}")
}

private fun Element.findChildElement(predicate: (Element) -> Boolean): Element? {
  return childNodes.asList().filterIsInstance<Element>().firstOrNull(predicate)
}
