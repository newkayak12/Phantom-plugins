// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.spring.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.annotations.NonNls

/**
 * Annotations that Spring applies through a proxy around the bean. Advice on them runs only when the call comes from
 * outside the bean, through the proxy, and only on methods the proxy can override.
 */
@NonNls
object SpringProxies {
  const val TRANSACTIONAL: String = "org.springframework.transaction.annotation.Transactional"
  const val JAKARTA_TRANSACTIONAL: String = "jakarta.transaction.Transactional"
  const val ASYNC: String = "org.springframework.scheduling.annotation.Async"
  const val RETRYABLE: String = "org.springframework.retry.annotation.Retryable"
  const val CACHEABLE: String = "org.springframework.cache.annotation.Cacheable"
  const val CONFIGURATION: String = "org.springframework.context.annotation.Configuration"

  val TRANSACTION_ANNOTATIONS: List<String> = listOf(TRANSACTIONAL, JAKARTA_TRANSACTIONAL)

  /** Short names for display, in the order they are reported. */
  val ADVICE: List<String> = TRANSACTION_ANNOTATIONS + listOf(
    ASYNC,
    CACHEABLE,
    "org.springframework.cache.annotation.CachePut",
    "org.springframework.cache.annotation.CacheEvict",
    "org.springframework.cache.annotation.Caching",
    RETRYABLE,
  )

  /** The first proxy annotation on [method] itself (directly or through a meta-annotation), or null. */
  fun methodAdvice(method: PsiMethod): PsiAnnotation? {
    for (annotation in method.annotations) {
      val fqn = annotation.qualifiedName ?: continue
      if (fqn in ADVICE) return annotation
      val type = annotation.resolveAnnotationType() ?: continue
      if (fqn.startsWith("java.") || fqn.startsWith("kotlin.")) continue
      if (MetaAnnotationUtil.isMetaAnnotated(type, ADVICE)) return annotation
    }
    return null
  }

  fun isTransactional(owner: com.intellij.psi.PsiModifierListOwner): Boolean = MetaAnnotationUtil.isMetaAnnotated(owner, TRANSACTION_ANNOTATIONS)

  /** Whether a call into [callee] from [caller] in the same bean loses something the proxy would have done. */
  fun selfCallLosesAdvice(callee: PsiMethod, caller: PsiMethod?): PsiAnnotation? {
    val advice = methodAdvice(callee) ?: return null
    val fqn = advice.qualifiedName
    if (fqn !in TRANSACTION_ANNOTATIONS) return advice
    // A transactional caller already has a transaction the callee would join, unless the callee asks for another one.
    val callerTransactional = caller != null && (isTransactional(caller) || caller.containingClass?.let(::isTransactional) == true)
    if (!callerTransactional) return advice
    val propagation = AnnotationUtil.findDeclaredAttribute(advice, "propagation")?.value?.text ?: return null
    return advice.takeIf { NEW_TRANSACTION.any { propagation.endsWith(it) } }
  }

  private val NEW_TRANSACTION = listOf("REQUIRES_NEW", "NOT_SUPPORTED", "NEVER", "NESTED")

  fun shortName(annotation: PsiAnnotation): String = "@" + (annotation.qualifiedName?.substringAfterLast('.') ?: annotation.text.removePrefix("@"))

  fun isStatic(method: PsiMethod): Boolean = method.hasModifierProperty(PsiModifier.STATIC)
}

/**
 * Kotlin classes are final unless the kotlin-spring (all-open) compiler plugin opens the ones Spring proxies. The IDE
 * cannot ask Gradle, so the build files are read: the plugin id anywhere in them, plus `allOpen { annotation("…") }`.
 */
object KotlinOpenPlugins {
  /** What kotlin-spring opens: classes carrying these (directly or through meta-annotations), and all their members. */
  val SPRING_OPENED: List<String> = listOf(
    SpringNames.COMPONENT,
    SpringProxies.TRANSACTIONAL,
    SpringProxies.ASYNC,
    SpringProxies.CACHEABLE,
    "org.springframework.boot.test.context.SpringBootTest",
  )

  class Settings(val springPlugin: Boolean, val allOpenAnnotations: Set<String>)

  private val SPRING_PLUGIN = Regex("""kotlin[-.]spring|plugin\.spring|kotlin-maven-allopen.*spring|<plugin>spring</plugin>""")
  private val ALL_OPEN_ANNOTATION = Regex("""annotation\s*\(\s*"([\w.$]+)"\s*\)|<option>all-open:annotation=([\w.$]+)</option>""")
  private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle", "pom.xml")

  fun settings(project: Project): Settings = CachedValuesManager.getManager(project).getCachedValue(project) {
    var spring = false
    val annotations = HashSet<String>()
    val scope = GlobalSearchScope.projectScope(project)
    for (name in BUILD_FILES) {
      for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
        val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
        if (SPRING_PLUGIN.containsMatchIn(text)) spring = true
        if (text.contains("allOpen") || text.contains("all-open")) {
          ALL_OPEN_ANNOTATION.findAll(text).forEach { m -> annotations += m.groupValues.drop(1).first { it.isNotEmpty() } }
        }
      }
    }
    CachedValueProvider.Result.create(Settings(spring, annotations), PsiModificationTracker.MODIFICATION_COUNT,
                                      com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
  }

  /** Whether a compiler plugin makes this Kotlin class (and its members) open. */
  fun opensClass(psiClass: PsiClass): Boolean {
    val settings = settings(psiClass.project)
    if (settings.springPlugin && MetaAnnotationUtil.isMetaAnnotated(psiClass, SPRING_OPENED)) return true
    return settings.allOpenAnnotations.isNotEmpty() && MetaAnnotationUtil.isMetaAnnotated(psiClass, settings.allOpenAnnotations.toList())
  }
}
