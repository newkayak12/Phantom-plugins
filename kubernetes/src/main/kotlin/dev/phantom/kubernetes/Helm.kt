// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

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

  /** `{{- define "name" -}}` names in the chart's templates. */
  fun defineNames(project: Project): Set<String> = index(project)?.defines?.keys.orEmpty()

  /** Where `define "name"` is: the first of the chart's templates that defines it. */
  fun define(project: Project, name: String): PsiElement? {
    val location = index(project)?.defines?.get(name) ?: return null
    return PsiManager.getInstance(project).findFile(location.file)?.let { it.findElementAt(location.offset) ?: it }
  }

  /** The `include`/`template` calls in the chart's templates, by template name. */
  fun usages(project: Project): Map<String, List<Location>> = index(project)?.usages.orEmpty()

  class Location(val file: VirtualFile, val offset: Int)

  private class Index(val defines: Map<String, Location>, val usages: Map<String, List<Location>>, val hasDependencies: Boolean)

  /**
   * The chart's defines, include calls and dependencies, kept on Chart.yaml until any PSI changes or files are created,
   * deleted or renamed. Templates are read as text (`.tpl` files are plain text), and every completion, inspection and
   * gutter of every template otherwise reread all templates of the chart.
   */
  private fun index(project: Project): Index? {
    val chartYaml = root.findChild("Chart.yaml")?.let { PsiManager.getInstance(project).findFile(it) } ?: return null
    return CachedValuesManager.getCachedValue(chartYaml, INDEX) {
      val defines = LinkedHashMap<String, Location>()
      val usages = HashMap<String, MutableList<Location>>()
      for (file in templateFiles()) {
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence
                   ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
        for (match in DEFINE.findAll(text)) defines.putIfAbsent(match.groupValues[1], Location(file, match.groups[1]!!.range.first))
        for (match in INCLUDE.findAll(text)) usages.getOrPut(match.groupValues[1]) { ArrayList() } += Location(file, match.groups[1]!!.range.first)
      }
      val hasDependencies = root.findChild("charts")?.children?.isNotEmpty() == true ||
                            Regex("""(?m)^dependencies\s*:""").containsMatchIn(chartYaml.viewProvider.contents)
      CachedValueProvider.Result.create(Index(defines, usages, hasDependencies),
                                        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
    }
  }

  /**
   * The charts whose values a template of this chart reads under `.Values.<name>`: unpacked subcharts in `charts/` by
   * folder name, and `dependencies` of Chart.yaml by alias or name. A dependency that is not unpacked maps to null.
   */
  fun subcharts(project: Project): Map<String, HelmChart?> {
    val result = HashMap<String, HelmChart?>()
    val chartYaml = root.findChild("Chart.yaml")?.let { PsiManager.getInstance(project).findFile(it) as? YAMLFile }
    val dependencies = (chartYaml?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping)?.getKeyValueByKey("dependencies")?.value as? YAMLSequence
    for (item in dependencies?.items.orEmpty()) {
      val mapping = item.value as? YAMLMapping ?: continue
      val name = (mapping.getKeyValueByKey("name")?.value as? YAMLScalar)?.textValue ?: continue
      val alias = (mapping.getKeyValueByKey("alias")?.value as? YAMLScalar)?.textValue
      result[alias ?: name] = root.findChild("charts")?.findChild(name)?.takeIf { it.findChild("Chart.yaml") != null }?.let(::HelmChart)
    }
    for (dir in root.findChild("charts")?.children.orEmpty()) {
      if (dir.isDirectory && dir.findChild("Chart.yaml") != null) result.putIfAbsent(dir.name, HelmChart(dir))
    }
    return result
  }

  /** The chart this one is unpacked in (`<parent>/charts/<this>`), whose values.yaml can set this chart's values. */
  fun parent(): HelmChart? {
    val charts = root.parent?.takeIf { it.name == "charts" } ?: return null
    return charts.parent?.takeIf { it.findChild("Chart.yaml") != null }?.let(::HelmChart)
  }

  /** Files under `templates/`, subfolders included. */
  fun templateFiles(): List<VirtualFile> {
    val templates = root.findChild("templates") ?: return emptyList()
    val result = ArrayList<VirtualFile>()
    VfsUtilCore.iterateChildrenRecursively(templates, null) { file ->
      if (!file.isDirectory) result.add(file)
      true
    }
    return result
  }

  /**
   * Whether templates can come from outside this chart: subcharts in `charts/` (library charts hold shared `define`s)
   * or `dependencies` in Chart.yaml.
   */
  fun hasDependencies(project: Project): Boolean = index(project)?.hasDependencies ?: false

  companion object {
    private val INDEX = Key.create<CachedValue<Index>>("phantom.helm.chart.index")
    val DEFINE = Regex("""\{\{-?\s*define\s+"([^"]+)"""")
    /** `include "name"` and `template "name"`; group 1 is the name. */
    val INCLUDE = Regex("""\b(?:include|template)\s+"([^"]+)"""")

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
  private val COMMENT = Regex("""^\{\{-?\s*/\*""")

  /** The actions in text order; comments (`{{/* … */}}`) are left out, since Helm does not evaluate them. */
  fun ranges(text: CharSequence): List<IntRange> = ACTION.findAll(text).filter { !COMMENT.containsMatchIn(it.value) }.map { it.range }.toList()

  /** Binary search: [ranges] are sorted and do not overlap, and a template can hold thousands of actions. */
  fun inAction(ranges: List<IntRange>, offset: Int): Boolean {
    val index = ranges.binarySearch { range ->
      when {
        offset < range.first -> 1
        offset > range.last -> -1
        else -> 0
      }
    }
    return index >= 0
  }
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
          chart.defineNames(file.project).forEach { names.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Template)) }
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
      return chart.define(file.project, match.groupValues[1])?.let { arrayOf(it) }
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
    val subcharts = chart.subcharts(file.project)
    val parent = chart.parent()
    val parentValues = parent?.values(file.project)
    // Helm merges a subchart's values.yaml under the parent's `.Values.<subchart>`, and the parent's values.yaml sets
    // values of its subcharts under their name: either side makes a key defined.
    fun defined(path: List<String>): Boolean {
      if (chart.defines(values, path)) return true
      if (path.first() in subcharts) {
        val subchart = subcharts[path.first()] ?: return true
        val subValues = subchart.values(file.project) ?: return true
        if (subchart.defines(subValues, path.drop(1))) return true
      }
      val parentTop = parentValues?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping ?: return false
      return parentTop.getKeyValueByKey(chart.root.name) != null && parent?.defines(parentValues, listOf(chart.root.name) + path) == true
    }
    val problems = ArrayList<ProblemDescriptor>()
    for (ref in ValuesReferences.all(text)) {
      if (!HelmActions.inAction(actions, ref.range.startOffset)) continue
      if (ref.path.first() == "global" || defined(ref.path)) continue
      val missing = ref.path.indices.first { !defined(ref.path.take(it + 1)) }
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
