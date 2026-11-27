// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.psi.PsiFile
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * `entityManager.createNamedQuery("Order.byCustomer")` whose name no `@NamedQuery` / `@NamedNativeQuery` of the
 * module's entities declares: the call fails with an IllegalArgumentException. Skipped when the project has an
 * `orm.xml` or a mapping file a `persistence.xml` names, which can declare named queries too.
 */
class JpaNamedQueryInspection : AbstractBaseUastLocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    val module = ModuleUtilCore.findModuleForPsiElement(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!JpaGate.isJpaModule(module)) return PsiElementVisitor.EMPTY_VISITOR
    val names by lazy { cachedNamedQueries(module) }
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        if (node.methodName != "createNamedQuery") return true
        val argument = node.valueArguments.firstOrNull() ?: return true
        val name = argument.evaluateString()?.takeIf { it.isNotBlank() } ?: return true
        val owner = node.resolve()?.containingClass?.qualifiedName ?: return true
        if (owner !in ENTITY_MANAGERS) return true
        val known = names ?: return true
        if (name in known) return true
        holder.registerProblem(argument.sourcePsi ?: return true, PhantomJpaBundle.message("named.query.unknown", name))
        return true
      }
    }, arrayOf(UCallExpression::class.java), true)
  }

  private companion object {
    val ENTITY_MANAGERS = setOf("jakarta.persistence.EntityManager", "javax.persistence.EntityManager")
    val SINGLE = JpaNamedQueries.SINGLE
    val CONTAINERS = JpaNamedQueries.CONTAINERS
    val MAPPING_FILE = Regex("""<mapping-file>([^<]+)</mapping-file>""")

    /**
     * Kept per module until JVM code, XML or the set of files changes: the annotation search covers all classes of the
     * module and its dependencies, and used to run again for every file highlighted.
     */
    private fun cachedNamedQueries(module: Module): Set<String>? {
      val project = module.project
      return CachedValuesManager.getManager(project).getCachedValue(module) {
        val xml = PsiModificationTracker.getInstance(project).forLanguages { it.id == "XML" }
        CachedValueProvider.Result.create(namedQueries(module), *JpaCacheDeps.code(project), xml, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
      }
    }

    /** Names of the named queries on the module's classes, or null when an XML mapping file may declare more. */
    private fun namedQueries(module: Module): Set<String>? {
      val project = module.project
      val projectScope = GlobalSearchScope.projectScope(project)
      if (mappingFileNames(module).any { FilenameIndex.getVirtualFilesByName(it, projectScope).isNotEmpty() }) return null
      val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
      val facade = JavaPsiFacade.getInstance(project)
      val names = HashSet<String>()
      for (fqn in SINGLE + CONTAINERS) {
        val annotationClass = facade.findClass(fqn, GlobalSearchScope.allScope(project)) ?: continue
        for (owner in AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()) {
          names.addAll(JpaNamedQueries.declaredOn(owner))
        }
      }
      return names
    }

    /** `orm.xml`, the default mapping file, and the file names of every `<mapping-file>` of the project's persistence.xml. */
    private fun mappingFileNames(module: Module): Set<String> {
      val project = module.project
      val names = linkedSetOf("orm.xml")
      for (file in FilenameIndex.getVirtualFilesByName("persistence.xml", GlobalSearchScope.projectScope(project))) {
        val text = PsiManager.getInstance(project).findFile(file)?.text ?: continue
        MAPPING_FILE.findAll(text).mapNotNull { it.groupValues[1].trim().substringAfterLast('/').takeIf(String::isNotEmpty) }.forEach(names::add)
      }
      return names
    }
  }
}

/** `@NamedQuery` / `@NamedNativeQuery` names, single or in their container annotations (JPA and Hibernate). */
internal object JpaNamedQueries {
  val SINGLE: List<String> = listOf("NamedQuery", "NamedNativeQuery").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it", "org.hibernate.annotations.$it") }
  val CONTAINERS: List<String> = listOf("NamedQueries", "NamedNativeQueries").flatMap { listOf("jakarta.persistence.$it", "javax.persistence.$it", "org.hibernate.annotations.$it") }

  fun declaredOn(owner: PsiClass): Set<String> {
    val names = HashSet<String>()
    for (annotation in owner.annotations) collect(annotation, names)
    return names
  }

  private fun collect(annotation: PsiAnnotation, names: MutableSet<String>) {
    val fqn = annotation.qualifiedName ?: return
    when (fqn) {
      in SINGLE -> AnnotationUtil.getStringAttributeValue(annotation, "name")?.let(names::add)
      in CONTAINERS -> AnnotationUtil.arrayAttributeValues(annotation.findDeclaredAttributeValue("value"))
        .filterIsInstance<PsiAnnotation>().forEach { collect(it, names) }
    }
  }
}
