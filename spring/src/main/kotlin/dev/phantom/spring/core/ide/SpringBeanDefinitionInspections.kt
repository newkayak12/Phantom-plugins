// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core.ide

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInspection.*
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.uast.UastHintedVisitorAdapter
import dev.phantom.spring.core.*
import org.jetbrains.annotations.NonNls
import org.jetbrains.uast.*
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

@NonNls
internal object SpringDefinitionNames {
  const val CACHE_PUT: String = "org.springframework.cache.annotation.CachePut"
  const val CONFIGURATION: String = "org.springframework.context.annotation.Configuration"
  const val LOOKUP: String = "org.springframework.beans.factory.annotation.Lookup"
  const val CONFIGURABLE: String = "org.springframework.beans.factory.annotation.Configurable"
  const val ENABLE_ASPECTJ_AUTO_PROXY: String = "org.springframework.context.annotation.EnableAspectJAutoProxy"
  const val BOOT_AOP_AUTO_CONFIGURATION: String = "org.springframework.boot.autoconfigure.aop.AopAutoConfiguration"
  const val COMPONENT_SCAN_FILTER: String = "org.springframework.context.annotation.ComponentScan.Filter"

  /** Stereotypes that only create a bean through component scanning, which skips abstract classes and interfaces. */
  val SCANNED_STEREOTYPES: List<String> = listOf(
    SpringNames.COMPONENT,
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController",
    CONFIGURATION,
  )
  const val REPOSITORY_STEREOTYPE: String = "org.springframework.stereotype.Repository"

  /** Annotations whose attributes name packages to scan, with those attributes. */
  val SCAN_PACKAGE_ATTRIBUTES: Map<String, List<String>> = mapOf(
    "org.springframework.context.annotation.ComponentScan" to listOf("value", "basePackages"),
    "org.springframework.boot.autoconfigure.SpringBootApplication" to listOf("scanBasePackages"),
    "org.springframework.boot.context.properties.ConfigurationPropertiesScan" to listOf("value", "basePackages"),
    "org.springframework.boot.autoconfigure.domain.EntityScan" to listOf("value", "basePackages"),
    "org.springframework.boot.persistence.autoconfigure.EntityScan" to listOf("value", "basePackages"),
    "org.springframework.data.jpa.repository.config.EnableJpaRepositories" to listOf("value", "basePackages"),
    "org.springframework.data.jdbc.repository.config.EnableJdbcRepositories" to listOf("value", "basePackages"),
    "org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories" to listOf("value", "basePackages"),
    "org.springframework.data.mongodb.repository.config.EnableMongoRepositories" to listOf("value", "basePackages"),
    "org.springframework.data.redis.repository.configuration.EnableRedisRepositories" to listOf("value", "basePackages"),
  )
}

/** The values of an annotation attribute: the items of an array initializer, or the single value. */
internal fun attributeItems(annotation: UAnnotation, attribute: String): List<UExpression> {
  val value = annotation.findDeclaredAttributeValue(attribute) ?: return emptyList()
  return if (value is UCallExpression && value.kind == UastCallKind.NESTED_ARRAY_INITIALIZER) value.valueArguments else listOf(value)
}

internal fun anchorOf(item: UExpression): PsiElement? = (item as? UInjectionHost)?.psiLanguageInjectionHost ?: item.sourcePsi

/** `@Cacheable` and `@CachePut` on one method: the put always runs the method, so the cached value is never read. */
class SpringCacheableAndCachePutInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitMethod(node: UMethod): Boolean {
        if (node.findAnnotation(SpringProxies.CACHEABLE) == null) return true
        val put = node.findAnnotation(SpringDefinitionNames.CACHE_PUT) ?: return true
        holder.registerProblem(put.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.cacheable.put.message"))
        return true
      }
    }, arrayOf(UMethod::class.java), true)
  }
}

/**
 * `@Component`, `@Service`… on an abstract class or an interface: component scanning only registers concrete classes
 * (an abstract class with `@Lookup` methods excepted), so no bean is created. Interfaces carrying any other
 * annotation (`@Mapper`, `@FeignClient`, `@HttpExchange`…) are left alone: another framework may implement them.
 */
class SpringAbstractBeanInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (psiClass.isAnnotationType || psiClass.isEnum || psiClass is PsiAnonymousClass) return true
        val annotations = node.uAnnotations
        val stereotype: UAnnotation
        val key: String
        if (psiClass.isInterface) {
          stereotype = annotations.firstOrNull { it.qualifiedName in SpringDefinitionNames.SCANNED_STEREOTYPES } ?: return true
          val others = annotations.filter { a ->
            val fqn = a.qualifiedName ?: return@filter true
            fqn !in SpringDefinitionNames.SCANNED_STEREOTYPES && !fqn.startsWith("java.lang.") && !fqn.startsWith("kotlin.")
          }
          if (others.isNotEmpty()) return true
          key = "inspection.abstract.bean.interface"
        }
        else {
          if (!psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) return true
          stereotype = annotations.firstOrNull {
            it.qualifiedName in SpringDefinitionNames.SCANNED_STEREOTYPES || it.qualifiedName == SpringDefinitionNames.REPOSITORY_STEREOTYPE
          } ?: return true
          if (psiClass.methods.any { it.hasAnnotation(SpringDefinitionNames.LOOKUP) }) return true
          key = "inspection.abstract.bean.abstract"
        }
        val name = "@" + stereotype.qualifiedName!!.substringAfterLast('.')
        holder.registerProblem(stereotype.sourcePsi ?: return true, PhantomSpringBundle.message(key, name))
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }
}

/** `@Value("abc")` with a literal (no placeholder, no SpEL) that Spring cannot convert to the number, boolean or char it injects. */
class SpringValueTypeInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        if (node.qualifiedName != SpringNames.VALUE) return true
        val value = node.findDeclaredAttributeValue("value") ?: return true
        val text = value.evaluateString() ?: return true
        if (text.contains("\${") || text.contains("#{")) return true
        val type = when (val owner = node.uastParent) {
          is UVariable -> owner.type
          is UMethod -> owner.uastParameters.singleOrNull()?.type
          else -> null
        } ?: return true
        val target = PsiPrimitiveType.getOptionallyUnboxedType(type) ?: return true
        if (converts(text, target)) return true
        holder.registerProblem(anchorOf(value) ?: return true, PhantomSpringBundle.message("inspection.value.type.message", text, type.presentableText))
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }

  companion object {
    private val HEX = Regex("-?(0[xX]|#)[0-9a-fA-F]+")
    private val BOOLEANS = setOf("true", "false", "on", "off", "yes", "no", "1", "0")

    /** Like Spring's default string converters: all whitespace removed, hex numbers allowed, an empty value means null. */
    fun converts(raw: String, type: PsiPrimitiveType): Boolean {
      val text = raw.filterNot(Char::isWhitespace)
      if (text.isEmpty()) return true
      fun integral(min: Long, max: Long): Boolean {
        val n = if (HEX.matches(text)) runCatching { java.lang.Long.decode(text) }.getOrNull() else text.toLongOrNull()
        return n != null && n in min..max
      }
      return when (type) {
        PsiTypes.booleanType() -> text.lowercase() in BOOLEANS
        PsiTypes.intType() -> integral(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        PsiTypes.longType() -> integral(Long.MIN_VALUE, Long.MAX_VALUE)
        PsiTypes.shortType() -> integral(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
        PsiTypes.byteType() -> integral(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())
        PsiTypes.doubleType(), PsiTypes.floatType() -> text.toDoubleOrNull() != null
        PsiTypes.charType() -> raw.length == 1
        else -> true
      }
    }
  }
}

/**
 * `@Bean` methods Spring rejects: a `void` one anywhere, and in a proxied `@Configuration` class (the default
 * `proxyBeanMethods = true`) a private or (Java) final one, or a final Java configuration class. Kotlin's final
 * defaults are left to the Kotlin inspection, which knows the compiler plugins.
 */
class SpringBeanMethodInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    val java = holder.file.language.isKindOf(JavaLanguage.INSTANCE)
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (!java || !psiClass.hasModifierProperty(PsiModifier.FINAL) || !isProxied(psiClass)) return true
        val anchor = node.uAnnotations.firstOrNull { it.qualifiedName == SpringDefinitionNames.CONFIGURATION }?.sourcePsi ?: node.uastAnchor?.sourcePsi ?: return true
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.bean.method.final.class"))
        return true
      }

      override fun visitMethod(node: UMethod): Boolean {
        val method = node.javaPsi
        if (method.isConstructor || !SpringBeans.isBeanMethod(method)) return true
        val anchor = node.uastAnchor?.sourcePsi ?: return true
        if (method.returnType == PsiTypes.voidType()) {
          holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.bean.method.void"))
          return true
        }
        if (method.hasModifierProperty(PsiModifier.STATIC)) return true
        val psiClass = method.containingClass ?: return true
        if (!isProxied(psiClass)) return true
        val reason = when {
          method.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
          java && method.hasModifierProperty(PsiModifier.FINAL) -> "final"
          else -> return true
        }
        holder.registerProblem(anchor, PhantomSpringBundle.message("inspection.bean.method.modifier", reason))
        return true
      }
    }, arrayOf(UClass::class.java, UMethod::class.java), true)
  }

  private fun isProxied(psiClass: PsiClass): Boolean {
    val configuration = psiClass.getAnnotation(SpringDefinitionNames.CONFIGURATION) ?: return false
    return AnnotationUtil.getBooleanAttributeValue(configuration, "proxyBeanMethods") != false
  }
}

/** A package to scan (`@ComponentScan`, `scanBasePackages`, `@EnableJpaRepositories`…) that exists in no source root or library. */
class SpringScanPackageInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    val facade = JavaPsiFacade.getInstance(holder.project)
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val attributes = SpringDefinitionNames.SCAN_PACKAGE_ATTRIBUTES[node.qualifiedName] ?: return true
        for (attribute in attributes) {
          for (item in attributeItems(node, attribute)) {
            val name = item.evaluateString()?.trim() ?: continue
            if (name.isEmpty() || name.any { it == '*' || it == '$' || it == '{' || it == '?' }) continue
            // several packages may share one string, separated as Spring's tokenizer allows
            for (pkg in name.split(',', ';', ' ', '\t', '\n').map(String::trim).filter(String::isNotEmpty)) {
              if (facade.findPackage(pkg) != null) continue
              holder.registerProblem(anchorOf(item) ?: continue, PhantomSpringBundle.message("inspection.scan.package.message", pkg))
            }
          }
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/**
 * An `@Aspect` bean in a project where nothing turns on auto-proxying: no `@EnableAspectJAutoProxy`, no
 * `<aop:aspectj-autoproxy>` and no Spring Boot (whose AOP auto-configuration does it). The advice never runs.
 */
class SpringAspectjAutoproxyInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || !AopModel.isAopProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val aspect = node.uAnnotations.firstOrNull { it.qualifiedName == AopModel.ASPECT } ?: return true
        if (!SpringBeans.isBeanClass(node.javaPsi) || autoProxying(holder.project)) return true
        holder.registerProblem(aspect.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.aspectj.autoproxy.message"))
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }

  private fun autoProxying(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val all = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)
    val enabled = facade.findClass(SpringDefinitionNames.BOOT_AOP_AUTO_CONFIGURATION, all) != null ||
                  facade.findClass(SpringDefinitionNames.ENABLE_ASPECTJ_AUTO_PROXY, all)?.let {
                    ReferencesSearch.search(it, GlobalSearchScope.projectScope(project)).findFirst() != null
                  } == true ||
                  !PsiSearchHelper.getInstance(project).processUsagesInNonJavaFiles("aspectj-autoproxy", { _, _, _ -> false }, GlobalSearchScope.projectScope(project))
    CachedValueProvider.Result.create(enabled, PsiModificationTracker.MODIFICATION_COUNT)
  }
}

/**
 * `@Autowired` members in a class Spring never creates: not a component, not returned or built by a `@Bean` method,
 * not named by a class literal (`@Import`, `@EntityListeners`, `validatedBy`…) or in a resource file, not a superclass
 * of anything. Its fields stay null. Only `@Autowired` is considered: `@Inject` is also used by other injectors.
 */
class SpringAutowiredOutsideBeanInspection : AbstractBaseUastLocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project) || SpringBeans.isInTestSources(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitClass(node: UClass): Boolean {
        val psiClass = node.javaPsi
        if (psiClass.isInterface || psiClass.isEnum || psiClass.isAnnotationType || psiClass is PsiAnonymousClass) return true
        if (psiClass.hasModifierProperty(PsiModifier.ABSTRACT) || psiClass.containingClass != null && !psiClass.hasModifierProperty(PsiModifier.STATIC)) return true
        val members: List<PsiModifierListOwner> = psiClass.fields.toList() + psiClass.methods.toList()
        if (members.none { it.hasAnnotation(SpringNames.AUTOWIRED) }) return true
        if (SpringBeans.isBeanClass(psiClass) || psiClass.annotations.isNotEmpty() && hasNonKotlinAnnotation(psiClass)) return true
        if (mayBeCreatedBySpring(psiClass)) return true
        holder.registerProblem(node.uastAnchor?.sourcePsi ?: return true, PhantomSpringBundle.message("inspection.autowired.outside.bean.message", psiClass.name ?: ""))
        return true
      }
    }, arrayOf(UClass::class.java), true)
  }

  /** Any annotation (`@Configurable`, `@WebServlet`, a JPA or Jackson one…) may mean something else instantiates and wires it. */
  private fun hasNonKotlinAnnotation(psiClass: PsiClass): Boolean =
    psiClass.annotations.any { a -> a.qualifiedName?.let { !it.startsWith("kotlin.") && !it.startsWith("java.lang.") } ?: true }

  private fun mayBeCreatedBySpring(psiClass: PsiClass): Boolean {
    val project = psiClass.project
    val scope = GlobalSearchScope.projectScope(project)
    if (ClassInheritorsSearch.search(psiClass, scope, false).findFirst() != null) return true
    // include filters can make any class a component
    JavaPsiFacade.getInstance(project).findClass(SpringDefinitionNames.COMPONENT_SCAN_FILTER, GlobalSearchScope.allScope(project))?.let { filter ->
      if (ReferencesSearch.search(filter, scope).findFirst() != null) return true
    }
    val qualifiedName = psiClass.qualifiedName ?: return true
    if (!PsiSearchHelper.getInstance(project).processUsagesInNonJavaFiles(qualifiedName, { _, _, _ -> false }, scope)) return true
    return !ReferencesSearch.search(psiClass, scope).forEach(com.intellij.util.Processor { reference -> !createsOrNames(reference.element) })
  }

  /** A reference that can make Spring create the class: inside a `@Bean` method, a class literal, a constructor reference. */
  private fun createsOrNames(element: PsiElement): Boolean {
    if (PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java) != null) return false
    if (element.parent is PsiClassObjectAccessExpression || element.parent?.parent is PsiClassObjectAccessExpression) return true
    if (element.parent is PsiMethodReferenceExpression) return true
    var e: PsiElement? = element
    var depth = 0
    while (e != null && e !is PsiFile) {
      val kind = e.javaClass.simpleName
      if (depth++ < 6 && (kind == "KtClassLiteralExpression" || kind == "KtCallableReferenceExpression" || kind == "KtDoubleColonExpression")) return true
      if (e is PsiMethod || kind == "KtNamedFunction") {
        val method = e.toUElementOfType<UMethod>()?.javaPsi
        if (method != null && SpringBeans.isBeanMethod(method)) return true
      }
      e = e.parent
    }
    return false
  }
}

/**
 * Resource locations Spring loads at startup that are not on the classpath: `@PropertySource` (unless
 * `ignoreResourceNotFound`), `@ImportResource`, and in tests `@TestPropertySource` and `@ContextConfiguration`.
 */
class SpringResourceLocationInspection : AbstractBaseUastLocalInspectionTool() {
  private class Target(val attributes: List<String>, val relativeToPackage: Boolean)

  private val targets = mapOf(
    "org.springframework.context.annotation.PropertySource" to Target(listOf("value"), false),
    "org.springframework.context.annotation.ImportResource" to Target(listOf("value", "locations"), false),
    "org.springframework.test.context.TestPropertySource" to Target(listOf("value", "locations"), true),
    "org.springframework.test.context.ContextConfiguration" to Target(listOf("value", "locations"), true),
  )

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!SpringProjectGate.isSpringProject(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(holder.file.language, object : AbstractUastNonRecursiveVisitor() {
      override fun visitAnnotation(node: UAnnotation): Boolean {
        val target = targets[node.qualifiedName] ?: return true
        if (node.findDeclaredAttributeValue("ignoreResourceNotFound")?.evaluate() == true) return true
        val owner = node.uastParent as? UClass ?: return true
        for (attribute in target.attributes) {
          for (item in attributeItems(node, attribute)) {
            val path = item.evaluateString() ?: continue
            if (path.isBlank() || path.contains("\${") || resourceExists(owner.javaPsi, path, target.relativeToPackage)) continue
            holder.registerProblem(anchorOf(item) ?: continue, PhantomSpringBundle.message("inspection.resource.location.message", path))
          }
        }
        return true
      }
    }, arrayOf(UAnnotation::class.java), true)
  }
}

/** Whether a Spring resource location resolves in the owner's module (sources, resources, libraries); URLs and patterns count as found. */
internal fun resourceExists(owner: PsiClass, raw: String, relativeToPackage: Boolean): Boolean {
  if (raw.startsWith("http:") || raw.startsWith("https:") || raw.startsWith("file:") || raw.startsWith("classpath*:")) return true
  val path = when {
    raw.startsWith("classpath:") -> raw.removePrefix("classpath:").trimStart('/')
    raw.startsWith("/") -> raw.trimStart('/')
    relativeToPackage -> (owner.qualifiedName?.substringBeforeLast('.', "")?.replace('.', '/')?.let { if (it.isEmpty()) "" else "$it/" } ?: "") + raw
    else -> raw
  }
  if (path.contains('*') || path.contains('?')) return true
  val module = ModuleUtilCore.findModuleForPsiElement(owner) ?: return true
  val entries = ModuleRootManager.getInstance(module).orderEntries().recursively().withoutSdk()
  val roots: List<VirtualFile> = entries.withoutLibraries().sources().roots.toList() + entries.librariesOnly().classes().roots.toList()
  return roots.any { it.findFileByRelativePath(path) != null }
}
