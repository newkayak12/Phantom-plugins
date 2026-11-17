// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.dockerfile

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.navigation.GotoRelatedItem
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.phantom.docker.compose.PhantomDockerBundle
import javax.swing.Icon

/**
 * On `FROM … AS <name>`: the instructions that use the stage (`COPY --from`, `RUN --mount=…,from=`, `FROM <name>`).
 * A Dockerfile has no PSI of its own (one text leaf, or TextMate tokens), so it is registered for every language,
 * returns at once for other files, and puts each marker on the leaf that holds the stage name.
 */
class DockerfileStageLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomDockerBundle.message("dockerfile.gutter.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

  override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
    val file = elements.firstOrNull()?.containingFile ?: return
    val dockerfile = Dockerfile.of(file) ?: return
    val text = file.viewProvider.contents
    val stages = DockerfileStageGotoDeclarationHandler.stages(dockerfile, text)
    if (stages.isEmpty()) return
    for (stage in stages) {
      val leaf = elements.firstOrNull { it.firstChild == null && it.textRange.containsOffset(stage.offset) } ?: continue
      val usages = DockerfileStageGotoDeclarationHandler.usages(dockerfile, text, stage, stages)
      if (usages.isEmpty()) continue
      val targets = usages.map { DockerfileStageGotoDeclarationHandler.StageTarget(file, it.startOffset, stage.name) }
      val renderer = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
        .setTargets(targets)
        .setTooltipText(PhantomDockerBundle.message("dockerfile.gutter.tooltip", stage.name, usages.size))
        .setPopupTitle(PhantomDockerBundle.message("dockerfile.gutter.popup", stage.name))
        .createGutterIconRenderer(file.project, null)
      val range = TextRange(stage.offset, stage.offset + stage.name.length).intersection(leaf.textRange) ?: continue
      val tooltip = renderer.tooltipText
      result += RelatedItemLineMarkerInfo(leaf, range, renderer.icon, tooltip?.let { t -> com.intellij.util.Function<PsiElement, String> { t } },
                                          if (renderer.isNavigateAction) renderer else null, renderer.alignment,
                                          { targets.map { GotoRelatedItem(it) } })
    }
  }
}
