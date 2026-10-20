// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.kubernetes

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

/**
 * Well-known labels, annotations and taint keys that Kubernetes renamed or stopped reading: as keys of `labels`,
 * `annotations` and `nodeSelector`, and as the `key`/`topologyKey` of affinity terms, tolerations and spread constraints.
 */
class K8sDeprecatedLabelsInspection : K8sInspection() {
  override fun check(resource: K8sResource, report: (PsiElement, String, ProblemHighlightType) -> Unit) {
    for (kv in PsiTreeUtil.findChildrenOfType(resource.document, YAMLKeyValue::class.java)) {
      val owner = (kv.parent as? YAMLMapping)?.parent as? YAMLKeyValue
      val name = when {
        owner?.keyText in setOf("labels", "annotations", "nodeSelector") -> kv.keyText
        kv.keyText == "key" || kv.keyText == "topologyKey" -> (kv.value as? YAMLScalar)?.textValue ?: continue
        else -> continue
      }
      val target = if (owner?.keyText in setOf("labels", "annotations", "nodeSelector")) kv.key ?: kv else kv.value ?: kv
      RENAMED[name]?.let { report(target, PhantomKubernetesBundle.message("k8s.label.deprecated", name, it), ProblemHighlightType.LIKE_DEPRECATED) }
      REMOVED.entries.firstOrNull { (prefix, _) -> if (prefix.endsWith("/")) name.startsWith(prefix) else name == prefix }?.let { (_, use) ->
        report(target, PhantomKubernetesBundle.message("k8s.label.removed", name, use), ProblemHighlightType.LIKE_DEPRECATED)
      }
    }
  }

  private companion object {
    val RENAMED = mapOf(
      "beta.kubernetes.io/arch" to "kubernetes.io/arch",
      "beta.kubernetes.io/os" to "kubernetes.io/os",
      "beta.kubernetes.io/instance-type" to "node.kubernetes.io/instance-type",
      "failure-domain.beta.kubernetes.io/zone" to "topology.kubernetes.io/zone",
      "failure-domain.beta.kubernetes.io/region" to "topology.kubernetes.io/region",
      "node-role.kubernetes.io/master" to "node-role.kubernetes.io/control-plane",
      "kubernetes.io/ingress.class" to "spec.ingressClassName",
    )

    /** Exact names, or prefixes ending in `/` for per-container annotations. */
    val REMOVED = mapOf(
      "seccomp.security.alpha.kubernetes.io/pod" to "securityContext.seccompProfile",
      "container.seccomp.security.alpha.kubernetes.io/" to "securityContext.seccompProfile",
      "container.apparmor.security.beta.kubernetes.io/" to "securityContext.appArmorProfile",
      "scheduler.alpha.kubernetes.io/critical-pod" to "priorityClassName",
    )
  }
}

/**
 * `include "name"` / `template "name"` with no `define "name"` in the chart's templates: Helm fails to render. Charts
 * with subcharts or `dependencies` are skipped, because library charts supply defines of their own.
 */
class HelmUnknownTemplateInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    val chart = HelmChart.of(file) ?: return null
    if (chart.hasDependencies()) return null
    val text = file.text
    val actions = HelmActions.ranges(text)
    val defined by lazy { chart.defines(file.project).keys + HelmChart.DEFINE.findAll(text).map { it.groupValues[1] } }
    val out = ArrayList<ProblemDescriptor>()
    for (match in HelmChart.INCLUDE.findAll(text)) {
      if (!HelmActions.inAction(actions, match.range.first)) continue
      val name = match.groupValues[1]
      if (name in defined) continue
      val range = match.groups[1]!!.range.let { TextRange(it.first, it.last + 1) }
      out += manager.createProblemDescriptor(file, range, PhantomKubernetesBundle.message("helm.template.unknown", name, chart.root.name),
                                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
    }
    return out.toTypedArray()
  }
}

/**
 * `{{-` at the start of a line whose action writes text: the trim removes the line break before it, so the output is
 * glued to the end of the previous line (`name: app{{ .Values.x }}`), which breaks the YAML. Reported only when the
 * previous line is plain YAML and the pipeline does not start a new line itself (`nindent`); control actions
 * (`if`, `range`, `end`, …), assignments and comments write nothing and are left alone. Helper files (`_*.tpl`) are skipped.
 */
class HelmWhitespaceTrimInspection : LocalInspectionTool() {
  override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
    if (HelmChart.of(file) == null || file.name.startsWith("_")) return null
    val text = file.text
    val out = ArrayList<ProblemDescriptor>()
    for (match in TRIMMED.findAll(text)) {
      val lineStart = text.lastIndexOf('\n', match.range.first - 1) + 1
      if (text.substring(lineStart, match.range.first).isNotBlank()) continue
      val body = match.groupValues[1].trim()
      if (!writesText(body)) continue
      if (lineStart == 0) continue
      val previousLine = previousNonBlankLine(text, lineStart) ?: continue
      if (previousLine.contains("{{") || previousLine.trimStart().startsWith("#")) continue
      out += manager.createProblemDescriptor(file, TextRange(match.range.first, match.range.first + 3), PhantomKubernetesBundle.message("helm.trim.joins"),
                                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly)
    }
    return out.toTypedArray()
  }

  private fun writesText(body: String): Boolean {
    if (body.isEmpty() || body.startsWith("/*")) return false
    val first = body.substringBefore(' ').substringBefore('(').trimEnd('-')
    if (first in CONTROL) return false
    if (ASSIGNMENT.containsMatchIn(body)) return false
    // the pipeline adds its own line break
    if (NINDENT.containsMatchIn(body)) return false
    return true
  }

  private fun previousNonBlankLine(text: String, lineStart: Int): String? {
    var end = lineStart - 1
    while (end >= 0) {
      val start = text.lastIndexOf('\n', end - 1) + 1
      val line = text.substring(start, end)
      if (line.isNotBlank()) return line
      if (start == 0) return null
      end = start - 1
    }
    return null
  }

  private companion object {
    val TRIMMED = Regex("""\{\{-\s(.*?)-?}}""")
    val CONTROL = setOf("if", "else", "end", "range", "with", "define", "block", "break", "continue", "fail")
    val ASSIGNMENT = Regex("""^\$[\w]*\s*:?=""")
    val NINDENT = Regex("""\|\s*nindent\b|^nindent\b""")
  }
}

/** On `define "name"`: the `include`/`template` calls of it in the chart. Registered for every language (`.tpl` is plain text). */
class HelmDefineLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomKubernetesBundle.message("helm.gutter.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    val file = elements.firstOrNull()?.containingFile ?: return
    val chart = HelmChart.of(file) ?: return
    val text = file.viewProvider.contents
    val defines = HelmChart.DEFINE.findAll(text).toList()
    if (defines.isEmpty()) return
    val usages = usages(chart, file)
    for (define in defines) {
      val name = define.groupValues[1]
      val found = usages[name].orEmpty()
      if (found.isEmpty()) continue
      val nameRange = define.groups[1]!!.range.let { TextRange(it.first, it.last + 1) }
      val leaf = elements.firstOrNull { it.firstChild == null && it.textRange.containsOffset(nameRange.startOffset) } ?: continue
      val range = nameRange.intersection(leaf.textRange) ?: continue
      val renderer = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
        .setTargets(found)
        .setTooltipText(PhantomKubernetesBundle.message("helm.gutter.tooltip", name, found.size))
        .setPopupTitle(PhantomKubernetesBundle.message("helm.gutter.popup", name))
        .createGutterIconRenderer(file.project, null)
      val tooltip = renderer.tooltipText
      result += RelatedItemLineMarkerInfo(leaf, range, renderer.icon, tooltip?.let { t -> com.intellij.util.Function<PsiElement, String> { t } },
                                          if (renderer.isNavigateAction) renderer else null, renderer.alignment,
                                          { found.map { GotoRelatedItem(it) } })
    }
  }

  private fun usages(chart: HelmChart, from: PsiFile): Map<String, List<PsiElement>> {
    val result = HashMap<String, MutableList<PsiElement>>()
    val manager = PsiManager.getInstance(from.project)
    for (vFile in chart.templateFiles()) {
      val psi = if (vFile == from.virtualFile) from else manager.findFile(vFile) ?: continue
      val text = psi.text
      for (match in HelmChart.INCLUDE.findAll(text)) {
        val group = match.groups[1]!!
        result.getOrPut(group.value) { ArrayList() } += OffsetTarget(psi, group.range.first, group.value)
      }
    }
    return result
  }
}

/** A place in a file (a `.tpl` has no PSI below the file): navigating opens the file there. */
internal class OffsetTarget(private val file: PsiFile, private val offset: Int, private val label: String) : FakePsiElement() {
  override fun getParent(): PsiElement = file
  override fun getContainingFile(): PsiFile = file
  override fun getTextOffset(): Int = offset
  override fun getTextRange(): TextRange = TextRange(offset, offset + label.length)
  override fun getName(): String = label
  override fun getPresentableText(): String = label
  override fun getLocationString(): String = file.name
  override fun isValid(): Boolean = file.isValid
  override fun canNavigate(): Boolean = true
  override fun navigate(requestFocus: Boolean) {
    val virtualFile = file.virtualFile ?: return
    OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
  }
}
