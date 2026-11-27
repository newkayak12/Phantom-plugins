// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getUParentForIdentifier
import javax.swing.Icon

/**
 * Gutter navigation between a Spring Data repository and its entity: on the repository to the entity, on the entity to
 * its repositories. Highlighting only checks annotations and supertypes; repositories are searched on click.
 */
class JpaRepositoryLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomJpaBundle.message("line.marker.name")

  override fun getIcon(): Icon = AllIcons.Gutter.DataSchema

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {

    if (GhostMode.enabled) return null
    if (element.firstChild != null) return null
    val owner = element.parent as? PsiNameIdentifierOwner ?: return null
    if (owner.nameIdentifier != element) return null
    val psiClass = (getUParentForIdentifier(element) as? UClass)?.javaPsi ?: return null
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!JpaGate.isJpaModule(module)) return null

    if (JpaModel.isEntity(psiClass)) {
      val name = psiClass.name ?: return null
      return NavigationGutterIconBuilder.create(AllIcons.Gutter.DataSchema)
        .setTargets(NotNullLazyValue.lazy { repositoriesOf(psiClass).map { it.navigationElement } })
        .setTooltipText(PhantomJpaBundle.message("gutter.entity.tooltip"))
        .setPopupTitle(PhantomJpaBundle.message("gutter.entity.popup.title", name))
        .setEmptyPopupText(PhantomJpaBundle.message("gutter.entity.empty", name))
        .createLineMarkerInfo(element)
    }
    if (psiClass.isInterface) {
      val entity = DerivedQuery.entityOf(psiClass)?.takeIf { JpaModel.isEntity(it) } ?: return null
      return NavigationGutterIconBuilder.create(AllIcons.Gutter.DataSchema)
        .setTargets(entity.navigationElement)
        .setTooltipText(PhantomJpaBundle.message("gutter.repository.tooltip", entity.name ?: ""))
        .createLineMarkerInfo(element)
    }
    return null
  }

  companion object {
    /** Repository interfaces in the project whose entity type argument is [entity]. */
    fun repositoriesOf(entity: PsiClass): List<PsiClass> {
      val project = entity.project
      val base = JavaPsiFacade.getInstance(project).findClass(DerivedQuery.REPOSITORY, GlobalSearchScope.allScope(project)) ?: return emptyList()
      return ClassInheritorsSearch.search(base, GlobalSearchScope.projectScope(project), true).findAll()
        .filter { it.isInterface && DerivedQuery.entityOf(it)?.isEquivalentTo(entity) == true }
        .sortedBy { it.name }
    }
  }
}
