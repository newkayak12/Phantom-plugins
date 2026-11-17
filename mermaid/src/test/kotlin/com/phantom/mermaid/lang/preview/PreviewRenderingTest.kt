// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.phantom.mermaid.lang.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.ui.jcef.JBCefBrowser
import com.phantom.mermaid.markdown.preview.MermaidDiagramPreviewComponent
import com.phantom.mermaid.markdown.preview.MermaidPreviewStaticServer
import com.phantom.mermaid.markdown.preview.createBrowser
import com.phantom.mermaid.markdown.preview.executeCancellableJavaScript
import com.phantom.mermaid.markdown.preview.waitForLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.ide.BuiltInServerManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Base64

/**
 * How often the preview pages draw a diagram. Mermaid adds a `d<id>` element to the page for every diagram it draws,
 * which the pages count here.
 */
@DisabledInAutomation
@PreviewTest
@WithJcef
@ExtendWith(WaitForBuiltInServerExtension::class)
@TestApplication
class PreviewRenderingTest {
  @JvmField
  @RegisterExtension
  val projectModel = ProjectModelExtension()

  @TestDisposable
  lateinit var disposable: Disposable

  private val project: Project
    get() = projectModel.project

  // language=JavaScript
  private val countDraws = """
    window.__draws = 0;
    new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(node => {
      if (node.id && node.id.startsWith("dmermaid-generated")) window.__draws++;
    }))).observe(document.body, {childList: true, subtree: true});
  """.trimIndent()

  private suspend fun JBCefBrowser.js(expression: String): String =
    executeCancellableJavaScript("new Promise(resolve => resolve(String($expression)));") ?: ""

  private suspend fun JBCefBrowser.waitFor(condition: String) {
    withTimeout(30_000) {
      while (js(condition) != "true") delay(50)
    }
  }

  private fun preview(block: suspend (MermaidDiagramPreviewComponent) -> Unit) = runBlocking(Dispatchers.EDT) {
    val preview = MermaidDiagramPreviewComponent(project)
    Disposer.register(disposable, preview)
    preview.load()
    preview.browser.js("(() => { $countDraws; return 1; })()")
    block(preview)
  }

  private val container = "document.getElementById('diagram-container').innerHTML"

  @Test
  fun `the diagram preview draws only the latest of quick edits`() = preview { preview ->
    // the edits in one go, as they reach the page while a large diagram is still drawn
    preview.browser.js("(() => { for (let i = 1; i <= 15; i++) window.updateMermaidDiagramContent(btoa('flowchart LR\\n  node' + i)); return 1; })()")
    preview.browser.waitFor("$container.includes('node15')")
    val draws = preview.browser.js("window.__draws").toInt()
    // one for the first text, one for the last; the edits in between arrive while the first is drawn
    assertTrue(draws in 1..3, "drawn $draws times for 15 edits")
  }

  @Test
  fun `the diagram preview does not draw the same text again`() = preview { preview ->
    preview.update("flowchart LR\n  alpha")
    preview.browser.waitFor("$container.includes('alpha')")
    assertEquals("1", preview.browser.js("window.__draws"))
    preview.update("flowchart LR\n  alpha")
    delay(500)
    preview.update("flowchart LR\n  beta")
    preview.browser.waitFor("$container.includes('beta')")
    assertEquals("2", preview.browser.js("window.__draws"))
  }

  private fun fence(cacheId: String, text: String) =
    """<div class="mermaid" data-cache-id="$cacheId" data-actual-fence-content="${Base64.getEncoder().encodeToString(text.toByteArray())}"></div>"""

  @Test
  fun `the Markdown preview draws only the diagrams that changed`() = runBlocking(Dispatchers.EDT) {
    // off-screen and created at once, as the preview creates its browser: a plain one never loads in the headless IDE
    val browser = createBrowser()
    Disposer.register(disposable, browser)
    // the built-in server answers only with its token
    val script = MermaidPreviewStaticServer.obtainStaticIndexUrl().replace("index.html", "mermaid.js").replace("isStandaloneViewer=true&", "").replace("?isStandaloneViewer=true", "?")
    // the page the Markdown preview builds: Incremental DOM patches it, then calls its listeners
    val page = """
      <html><head><script>
        window.IncrementalDOM = {notifications: {afterPatchListeners: []}};
        window.mermaidTheme = "default";
      </script></head><body>
        <div id="host">${fence("one", "flowchart LR\n  first")}${fence("two", "flowchart LR\n  second")}</div>
        <script>$countDraws</script>
        <script src="$script"></script>
      </body></html>
    """.trimIndent()
    val url = "http://localhost:${BuiltInServerManager.getInstance().port}/mermaidPreview/markdown-test.html"
    withTimeout(30_000) { browser.waitForLoad { loadHTML(page, url) } }
    val drawn = "[...document.querySelectorAll('div.mermaid')].every(block => block.querySelector('svg'))"
    browser.waitFor(drawn)
    assertEquals("2", browser.js("window.__draws"))

    // a patch replaces the blocks with empty ones, and the second diagram's text changed
    browser.js("""(() => {
      document.getElementById("host").innerHTML = `${fence("one", "flowchart LR\n  first")}${fence("three", "flowchart LR\n  third")}`;
      IncrementalDOM.notifications.afterPatchListeners.forEach(listener => listener());
      return 1;
    })()""")
    browser.waitFor("$drawn && document.getElementById('host').innerHTML.includes('third')")
    assertEquals("3", browser.js("window.__draws"), "the unchanged diagram came from the cache")

    // a patch that keeps the blocks leaves their diagrams as they are
    browser.js("(() => { document.querySelector('div.mermaid svg').setAttribute('data-kept', 'yes'); IncrementalDOM.notifications.afterPatchListeners.forEach(listener => listener()); return 1; })()")
    delay(500)
    assertEquals("yes", browser.js("document.querySelector('div.mermaid svg').getAttribute('data-kept')"))
    assertEquals("3", browser.js("window.__draws"))
  }
}
