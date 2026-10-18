// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import dev.phantom.spring.core.PhantomSpringBundle
import dev.phantom.spring.core.SpringProjectGate
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import javax.swing.Icon

/** Who publishes an event type and who listens to it, found by type when the gutter icon is clicked. */
object SpringEventModel {
  @NonNls const val PUBLISHER: String = "org.springframework.context.ApplicationEventPublisher"
  @NonNls const val PUBLISH: String = "publishEvent"

  class Listener(val method: PsiMethod, val types: List<PsiType>)

  /** Listener methods in project sources, cached until the next PSI change. */
  fun listeners(project: Project): List<Listener> = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val all = GlobalSearchScope.allScope(project)
    val scope = GlobalSearchScope.projectScope(project)
    val methods = LinkedHashSet<PsiMethod>()
    for (fqn in listOf(SpringMethodContracts.EVENT_LISTENER, SpringMethodContracts.TRANSACTIONAL_EVENT_LISTENER)) {
      val annotation = facade.findClass(fqn, all) ?: continue
      AnnotatedElementsSearch.searchPsiMethods(annotation, scope).forEach { methods.add(it) }
      // annotations built on it, such as @ApplicationModuleListener
      AnnotatedElementsSearch.searchPsiClasses(annotation, all).filter { it.isAnnotationType }.forEach { meta ->
        AnnotatedElementsSearch.searchPsiMethods(meta, scope).forEach { methods.add(it) }
      }
    }
    val result = methods.map { Listener(it, SpringEvents.eventTypes(it)) }.filter { it.types.isNotEmpty() }
    CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
  }

  fun isPublishCall(call: UCallExpression): Boolean {
    if (call.methodName != PUBLISH || call.valueArgumentCount != 1) return false
    val method = call.resolve() ?: return false
    val owner = method.containingClass ?: return false
    return InheritanceUtil.isInheritor(owner, false, PUBLISHER) || owner.qualifiedName == PUBLISHER
  }

  fun publishedType(call: UCallExpression): PsiType? = call.valueArguments.firstOrNull()?.getExpressionType()

  fun listenersOf(project: Project, published: PsiType): List<PsiMethod> =
    listeners(project).filter { l -> l.types.any { accepts(it, published) } }.map { it.method }

  /** Calls of `publishEvent` in project sources whose argument a listener of [types] receives. */
  fun publishersOf(project: Project, types: List<PsiType>): List<PsiElement> {
    val publisher = JavaPsiFacade.getInstance(project).findClass(PUBLISHER, GlobalSearchScope.allScope(project)) ?: return emptyList()
    val result = ArrayList<PsiElement>()
    for (method in publisher.findMethodsByName(PUBLISH, false)) {
      MethodReferencesSearch.search(method, GlobalSearchScope.projectScope(project), true).forEach { reference ->
        val call = reference.element.toUElement()?.getParentOfType<UCallExpression>(false) ?: return@forEach
        val type = publishedType(call) ?: return@forEach
        if (types.any { accepts(it, type) }) call.sourcePsi?.let(result::add)
      }
    }
    return result.distinct()
  }

  private fun accepts(listened: PsiType, published: PsiType): Boolean =
    runCatching { listened.isAssignableFrom(published) }.getOrDefault(false) || listened.canonicalText == published.canonicalText
}

/** Gutter icons between `publishEvent(event)` calls and the `@EventListener` methods that receive the event. */
class SpringEventLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomSpringBundle.message("event.line.marker.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val text = element.text
    val declaration = (element.parent as? PsiNameIdentifierOwner)?.takeIf { it.nameIdentifier == element }
    if (declaration == null && text != SpringEventModel.PUBLISH) return null
    if (!SpringProjectGate.isSpringProject(element.project)) return null
    val project = element.project
    if (declaration != null) {
      val method = getUParentForIdentifier(element) as? UMethod ?: return null
      val types = SpringEvents.eventTypes(method.javaPsi).ifEmpty { return null }
      val names = types.joinToString(", ") { it.presentableText }
      return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
        .setTargets(NotNullLazyValue.lazy { SpringEventModel.publishersOf(project, types) })
        .setTooltipText(PhantomSpringBundle.message("event.gutter.publishers", names))
        .setPopupTitle(PhantomSpringBundle.message("event.gutter.publishers", names))
        .setEmptyPopupText(PhantomSpringBundle.message("event.gutter.no.publishers"))
        .createLineMarkerInfo(element)
    }
    val call = element.getUastParentOfType(UCallExpression::class.java) ?: return null
    if (call.methodIdentifier?.sourcePsi != element && call.methodIdentifier?.sourcePsi != element.parent) return null
    if (!SpringEventModel.isPublishCall(call)) return null
    val type = SpringEventModel.publishedType(call) ?: return null
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(NotNullLazyValue.lazy { SpringEventModel.listenersOf(project, type).map { it.navigationElement } })
      .setTooltipText(PhantomSpringBundle.message("event.gutter.listeners", type.presentableText))
      .setPopupTitle(PhantomSpringBundle.message("event.gutter.listeners", type.presentableText))
      .setEmptyPopupText(PhantomSpringBundle.message("event.gutter.no.listeners"))
      .createLineMarkerInfo(element)
  }
}
