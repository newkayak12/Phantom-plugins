// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.DynamicBundle
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

private const val BUNDLE = "messages.PhantomKubernetesBundle"

object PhantomKubernetesBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/**
 * A Helm chart seen from one of its template files: `<chart>/templates/…` next to `<chart>/Chart.yaml`. Everything in
 * this plugin starts by asking for it and stops when the file is not a chart template, so other YAML costs nothing.
 */
internal class HelmChart(val root: VirtualFile) {
  fun values(project: Project): YAMLFile? =
    (root.findChild("values.yaml") ?: root.findChild("values.yml"))?.let { PsiManager.getInstance(project).findFile(it) as? YAMLFile }

  /** The top-level mapping of values.yaml, followed along [path]; null when a key is missing. */
  fun mappingAt(values: YAMLFile, path: List<String>): YAMLMapping? {
    var mapping = values.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return null
    for (key in path) mapping = mapping.getKeyValueByKey(key)?.value as? YAMLMapping ?: return null
    return mapping
  }

  fun keyAt(values: YAMLFile, path: List<String>): YAMLKeyValue? {
    if (path.isEmpty()) return null
    return mappingAt(values, path.dropLast(1))?.getKeyValueByKey(path.last())
  }

  /**
   * Whether `.Values.a.b.c` can have a value: every key is in values.yaml, or a key on the way holds null or `{}`,
   * which charts use for maps the user fills in (`podAnnotations: {}`).
   */
  fun defines(values: YAMLFile, path: List<String>): Boolean {
    var mapping = values.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return true
    for ((index, key) in path.withIndex()) {
      val keyValue = mapping.getKeyValueByKey(key) ?: return false
      if (index == path.lastIndex) return true
      val value = keyValue.value
      mapping = when {
        value == null -> return true
        value is YAMLMapping -> if (value.keyValues.isEmpty()) return true else value
        value is YAMLScalar && value.textValue.let { it == "~" || it == "null" || it.isEmpty() } -> return true
        else -> return false
      }
    }
    return true
  }

  /** `{{- define "name" -}}` blocks in the chart's templates, by name. */
  fun defines(project: Project): Map<String, PsiElement> {
    val result = LinkedHashMap<String, PsiElement>()
    val templates = root.findChild("templates") ?: return result
    for (file in templates.children) {
      if (file.isDirectory) continue
      val psi = PsiManager.getInstance(project).findFile(file) ?: continue
      for (match in DEFINE.findAll(psi.text)) {
        psi.findElementAt(match.groups[1]!!.range.first)?.let { result.putIfAbsent(match.groupValues[1], it) }
      }
    }
    return result
  }

  companion object {
    private val DEFINE = Regex("""\{\{-?\s*define\s+"([^"]+)"""")

    fun of(file: PsiFile?): HelmChart? {
      val virtualFile = file?.originalFile?.virtualFile ?: return null
      var dir = virtualFile.parent ?: return null
      // templates/ or a subfolder of it
      repeat(4) {
        if (dir.name == "templates") {
          val root = dir.parent ?: return null
          return if (root.findChild("Chart.yaml") != null) HelmChart(root) else null
        }
        dir = dir.parent ?: return null
      }
      return null
    }
  }
}

/** `.Values.a.b`, `$.Values.a.b`: the path and where each segment sits in the text. */
internal object ValuesReferences {
  val REFERENCE = Regex("""(?<![\w$])\$?\.Values((?:\.[A-Za-z_]\w*)+)""")

  class Ref(val range: TextRange, val segments: List<Pair<String, TextRange>>) {
    val path: List<String> get() = segments.map { it.first }
  }

  fun all(text: CharSequence): Sequence<Ref> = REFERENCE.findAll(text).map { match ->
    val pathStart = match.groups[1]!!.range.first
    var offset = pathStart
    val segments = match.groupValues[1].split('.').drop(1).map { name ->
      offset += 1
      val range = TextRange(offset, offset + name.length)
      offset += name.length
      name to range
    }
    Ref(TextRange(match.range.first, match.range.last + 1), segments)
  }
}

/** Template text that Helm evaluates: `{{ … }}` actions. References outside them are plain text. */
internal object HelmActions {
  private val ACTION = Regex("""\{\{.*?}}""", RegexOption.DOT_MATCHES_ALL)

  fun ranges(text: CharSequence): List<IntRange> = ACTION.findAll(text).map { it.range }.toList()

  fun inAction(ranges: List<IntRange>, offset: Int): Boolean = ranges.any { offset in it }
}

/** Completion in chart templates: `.Values` keys from values.yaml, `.Release`/`.Chart` fields, and `include` names. */
class HelmCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val file = parameters.originalFile
        val chart = HelmChart.of(file) ?: return
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val before = text.subSequence(lineStart, offset).toString()

        INCLUDE.find(before)?.let { match ->
          val names = result.withPrefixMatcher(match.groupValues[1])
          chart.defines(file.project).keys.forEach { names.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Template)) }
          result.stopHere()
          return
        }
        BUILTIN.find(before)?.let { match ->
          val names = result.withPrefixMatcher(match.groupValues[2])
          BUILTINS.getValue(match.groupValues[1]).forEach { names.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Property)) }
          result.stopHere()
          return
        }
        val match = VALUES_PATH.find(before) ?: return
        val values = chart.values(file.project) ?: return
        val parent = match.groupValues[1].split('.').filter { it.isNotEmpty() }
        val mapping = chart.mappingAt(values, parent) ?: return
        val keys = result.withPrefixMatcher(match.groupValues[2])
        for (keyValue in mapping.keyValues) {
          val preview = (keyValue.value as? YAMLScalar)?.textValue ?: if (keyValue.value is YAMLMapping) "{…}" else ""
          keys.addElement(LookupElementBuilder.create(keyValue.keyText).withIcon(AllIcons.Json.Object).withTypeText(preview.take(40), true))
        }
        result.stopHere()
      }
    })
  }

  private companion object {
    val VALUES_PATH = Regex("""(?<![\w$])\$?\.Values((?:\.[A-Za-z_]\w*)*)\.(\w*)$""")
    val BUILTIN = Regex("""(?<![\w$])\$?\.(Release|Chart|Capabilities)\.(\w*)$""")
    val INCLUDE = Regex("""(?:include|template)\s+"([^"]*)$""")
    val BUILTINS = mapOf(
      "Release" to listOf("Name", "Namespace", "Service", "IsUpgrade", "IsInstall", "Revision"),
      "Chart" to listOf("Name", "Version", "AppVersion", "Description", "Type", "KubeVersion", "Home"),
      "Capabilities" to listOf("APIVersions", "KubeVersion", "HelmVersion"),
    )
  }
}

/** Typing `.` in a chart template opens completion, as it does in code. */
class HelmTypedHandler : TypedHandlerDelegate() {
  override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (charTyped == '.' && HelmChart.of(file) != null) {
      AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
      return Result.STOP
    }
    return Result.CONTINUE
  }
}

/** Ctrl+B from `.Values.a.b` to the key in values.yaml, and from `include "name"` to its `define`. */
class HelmGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    val file = sourceElement?.containingFile ?: return null
    val chart = HelmChart.of(file) ?: return null
    val text = editor.document.charsSequence
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val line = text.subSequence(lineStart, lineEnd)

    for (ref in ValuesReferences.all(line)) {
      val index = ref.segments.indexOfFirst { (_, range) -> offset - lineStart in range.startOffset..range.endOffset }
      if (index < 0) continue
      val values = chart.values(file.project) ?: return null
      val key = chart.keyAt(values, ref.path.take(index + 1)) ?: return null
      return arrayOf(key)
    }
    for (match in INCLUDE_NAME.findAll(line)) {
      if (offset - lineStart !in match.groups[1]!!.range.first..match.groups[1]!!.range.last + 1) continue
      return chart.defines(file.project)[match.groupValues[1]]?.let { arrayOf(it) }
    }
    return null
  }

  private companion object {
    val INCLUDE_NAME = Regex("""(?:include|template)\s+"([^"]+)"""")
  }
}

/**
 * `.Values.a.b` in a template that values.yaml does not define. Helm renders a missing value as empty (or fails in
 * `required`), so a typo shows up only after deploying. `.Values.global` is skipped: parent charts supply it.
 */
class HelmValuesInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val chart = HelmChart.of(file) ?: return null
    val values = chart.values(file.project) ?: return null
    val text = file.text
    val actions = HelmActions.ranges(text)
    val problems = ArrayList<ProblemDescriptor>()
    for (ref in ValuesReferences.all(text)) {
      if (!HelmActions.inAction(actions, ref.range.startOffset)) continue
      if (ref.path.first() == "global" || chart.defines(values, ref.path)) continue
      val missing = ref.path.indices.first { !chart.defines(values, ref.path.take(it + 1)) }
      val range = TextRange(ref.segments[missing].second.startOffset, ref.range.endOffset)
      val start = file.findElementAt(range.startOffset) ?: continue
      val end = file.findElementAt(range.endOffset - 1) ?: continue
      val holder = PsiTreeUtil.findCommonParent(start, end) ?: continue
      val inHolder = range.shiftLeft(holder.textRange.startOffset)
      problems += manager.createProblemDescriptor(holder, inHolder,
                                                  PhantomKubernetesBundle.message("values.missing", ref.path.take(missing + 1).joinToString("."), chart.root.name),
                                                  ProblemHighlightType.WEAK_WARNING, isOnTheFly)
    }
    return problems.toTypedArray()
  }
}
