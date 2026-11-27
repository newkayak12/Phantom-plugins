// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core.exec

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.phantom.http.core.PhantomHttpBundle
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.charset.Charset
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JPanel

/** Text and file type of a response body, prepared off the EDT. */
class PreparedBody(val text: String, val fileType: FileType, val extension: String)

object HttpResponseFormatter {
  private val PRETTY = Json { prettyPrint = true }
  const val PLAIN_TEXT_ABOVE: Int = 1024 * 1024

  fun prepare(response: HttpResponseModel): PreparedBody {
    val contentType = response.contentType.orEmpty().lowercase()
    val charset = Regex("""charset=([\w.-]+)""").find(contentType)?.groupValues?.get(1)
      ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    val raw = StringUtil.convertLineSeparators(String(response.body, charset))
    val extension = when {
      // Pretty-printing and highlighting a body of several megabytes freezes the viewer, so large ones stay plain text.
      response.body.size > PLAIN_TEXT_ABOVE -> "txt"
      contentType.contains("json") -> "json"
      contentType.contains("html") -> "html"
      contentType.contains("xml") -> "xml"
      else -> "txt"
    }
    val text = if (extension == "json" && !response.truncated) prettyJson(raw) ?: raw else raw
    val fileType = if (extension == "txt") PlainTextFileType.INSTANCE else FileTypeManager.getInstance().getFileTypeByExtension(extension)
    return PreparedBody(text, fileType, extension)
  }

  fun prettyJson(text: String): String? = try {
    PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
  }
  catch (_: Exception) {
    null
  }

  fun size(bytes: Int, truncated: Boolean): String = (if (truncated) "> " else "") + StringUtil.formatFileSize(bytes.toLong())
}

/**
 * One tab of the "HTTP Client" tool window. Shows "Sending…" with a Stop link while the request runs, then the
 * status line, a one-line header summary (expands on click) and the body.
 */
class HttpResponsePanel(private val project: Project, private val title: String) : JPanel(BorderLayout()), Disposable {
  private var editor: Editor? = null
  var job: Job? = null

  init {
    border = JBUI.Borders.empty()
  }

  fun showSending(requestLine: String) {
    val top = row()
    top.add(JBLabel(PhantomHttpBundle.message("http.response.sending", requestLine), AllIcons.Process.Step_1, JBLabel.LEFT))
    top.add(ActionLink(PhantomHttpBundle.message("http.response.stop")) { job?.cancel() })
    replace(top, null)
  }

  fun showError(requestLine: String?, message: String) {
    val top = row()
    top.add(JBLabel(message, AllIcons.General.Error, JBLabel.LEFT).apply { foreground = JBColor.RED })
    val center = requestLine?.let { JBLabel(it).apply { border = JBUI.Borders.empty(0, 8); foreground = UIUtil.getContextHelpForeground() } }
    replace(top, center)
  }

  fun showResponse(request: HttpRequestModel, response: HttpResponseModel, body: PreparedBody, report: HttpHandlerReport? = null) {
    val header = JPanel(BorderLayout())
    val status = row()
    val reason = REASONS[response.status].orEmpty()
    status.add(JBLabel("${response.status} $reason".trim()).apply {
      foreground = when (response.status / 100) {
        2 -> JBColor(0x2E7D32, 0x6AAB73)
        3 -> JBColor.BLUE
        else -> JBColor.RED
      }
      font = font.deriveFont(java.awt.Font.BOLD)
    })
    status.add(JBLabel("${response.elapsedMs} ms · ${HttpResponseFormatter.size(response.body.size, response.truncated)}").apply {
      foreground = UIUtil.getContextHelpForeground()
    })
    status.add(JBLabel(request.requestLine).apply { foreground = UIUtil.getContextHelpForeground() })
    header.add(status, BorderLayout.NORTH)

    val headerText = JBTextArea(response.headers.joinToString("\n") { "${it.first}: ${it.second}" }).apply {
      isEditable = false
      font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
      border = JBUI.Borders.empty(4, 8)
    }
    val headerScroll = JBScrollPane(headerText).apply { isVisible = false; border = JBUI.Borders.empty() }
    val summary = row()
    val contentType = response.contentType?.let { "Content-Type: $it · " }.orEmpty()
    summary.add(ActionLink(contentType + PhantomHttpBundle.message("http.response.headers", response.headers.size)) {
      headerScroll.isVisible = !headerScroll.isVisible
      header.revalidate()
    })
    summary.add(ActionLink(PhantomHttpBundle.message("http.response.open.editor")) { openInEditor(body) })
    for (warning in response.warnings) summary.add(JBLabel(warning, AllIcons.General.Warning, JBLabel.LEFT))
    val details = JPanel(com.intellij.ui.components.panels.VerticalLayout(0))
    if (report != null) handlerRows(report).forEach(details::add)
    details.add(headerScroll)
    headerScroll.isVisible = false
    header.add(summary, BorderLayout.CENTER)
    header.add(details, BorderLayout.SOUTH)

    replace(header, bodyEditor(body).component)
  }

  /** Test results first (failed ones with their message), then handler errors, then `client.log` lines. */
  private fun handlerRows(report: HttpHandlerReport): List<JComponent> {
    val out = ArrayList<JComponent>()
    if (report.tests.isNotEmpty()) {
      val line = row()
      val passed = report.tests.size - report.failed
      line.add(JBLabel(PhantomHttpBundle.message("http.handler.tests", passed, report.tests.size),
                       if (report.failed == 0) AllIcons.RunConfigurations.TestPassed else AllIcons.RunConfigurations.TestFailed, JBLabel.LEFT))
      out.add(line)
      for (test in report.tests.filter { !it.passed }) {
        out.add(row().apply {
          add(JBLabel("${test.name}: ${test.message.orEmpty()}", AllIcons.RunConfigurations.TestFailed, JBLabel.LEFT).apply { foreground = JBColor.RED })
        })
      }
    }
    for (error in report.errors) {
      out.add(row().apply { add(JBLabel(error, AllIcons.General.Error, JBLabel.LEFT).apply { foreground = JBColor.RED }) })
    }
    if (report.logs.isNotEmpty()) {
      val text = JBTextArea(report.logs.joinToString("\n")).apply {
        isEditable = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        border = JBUI.Borders.empty(4, 8)
        rows = minOf(report.logs.size, 6)
      }
      out.add(JBScrollPane(text).apply { border = JBUI.Borders.empty() })
    }
    return out
  }

  private fun bodyEditor(body: PreparedBody): Editor {
    releaseEditor()
    val document = EditorFactory.getInstance().createDocument(body.text)
    document.setReadOnly(true)
    val viewer = EditorFactory.getInstance().createEditor(document, project, body.fileType, true)
    viewer.settings.apply {
      isLineNumbersShown = false
      isFoldingOutlineShown = true
      additionalLinesCount = 0
      isCaretRowShown = false
    }
    editor = viewer
    return viewer
  }

  private fun openInEditor(body: PreparedBody) {
    val file = LightVirtualFile("$title.${body.extension}", body.fileType, body.text)
    FileEditorManager.getInstance(project).openFile(file, true)
  }

  @get:org.jetbrains.annotations.TestOnly
  val shownBody: String? get() = editor?.document?.text

  @get:org.jetbrains.annotations.TestOnly
  val shownLabels: List<String> get() = com.intellij.util.ui.UIUtil.findComponentsOfType(this, JBLabel::class.java).map { it.text }

  private fun row(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))

  private fun replace(north: JComponent, center: JComponent?) {
    removeAll()
    add(north, BorderLayout.NORTH)
    if (center != null) add(center, BorderLayout.CENTER)
    revalidate()
    repaint()
  }

  private fun releaseEditor() {
    editor?.let { EditorFactory.getInstance().releaseEditor(it) }
    editor = null
  }

  override fun dispose() {
    job?.cancel()
    releaseEditor()
  }

  companion object {
    private val REASONS = mapOf(
      200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content", 301 to "Moved Permanently", 302 to "Found",
      303 to "See Other", 304 to "Not Modified", 307 to "Temporary Redirect", 308 to "Permanent Redirect", 400 to "Bad Request",
      401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict",
      415 to "Unsupported Media Type", 422 to "Unprocessable Content", 429 to "Too Many Requests", 500 to "Internal Server Error",
      502 to "Bad Gateway", 503 to "Service Unavailable", 504 to "Gateway Timeout",
    )
  }
}

/**
 * The "HTTP Client" tool window. It is registered on the first run, so it costs nothing and shows no stripe button
 * before that. Keeps the last [MAX_TABS] tabs; pinned tabs stay.
 */
object HttpResponseToolWindow {
  const val ID: String = "Phantom HTTP Client"
  private const val MAX_TABS = 10

  fun newTab(project: Project, title: String): HttpResponsePanel {
    val toolWindow = toolWindow(project)
    val manager = toolWindow.contentManager
    val panel = HttpResponsePanel(project, title)
    val content = ContentFactory.getInstance().createContent(panel, title, false)
    content.setDisposer(panel)
    manager.addContent(content)
    manager.setSelectedContent(content)
    val unpinned = manager.contents.filter { !it.isPinned }
    if (unpinned.size > MAX_TABS) unpinned.take(unpinned.size - MAX_TABS).forEach { manager.removeContent(it, true) }
    toolWindow.show()
    return panel
  }

  private fun toolWindow(project: Project): ToolWindow {
    val manager = ToolWindowManager.getInstance(project)
    return manager.getToolWindow(ID) ?: manager.registerToolWindow(ID) {
      icon = AllIcons.General.Web
      stripeTitle = Supplier { PhantomHttpBundle.message("http.toolwindow.title") }
      canCloseContent = true
      hideOnEmptyContent = false
    }
  }
}
