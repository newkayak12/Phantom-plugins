// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.module.Module
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtil
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.evaluateString

/**
 * Mapping annotations read the same way for `jakarta.persistence` and `javax.persistence`, from UAST so Kotlin
 * constructor properties count as well as Java fields.
 */
object JpaMapping {
  val RELATIONS: List<String> = listOf("ManyToOne", "OneToOne", "OneToMany", "ManyToMany")
  private val TO_MANY = setOf("OneToMany", "ManyToMany")
  /** Annotations that give a non-basic Java type a mapping of its own. */
  private val TYPE_MAPPINGS = listOf("ElementCollection", "Convert", "Embedded", "EmbeddedId", "Lob", "Transient") + RELATIONS
  private val HIBERNATE_TYPE_MAPPINGS = listOf(
    "org.hibernate.annotations.Type", "org.hibernate.annotations.JdbcTypeCode", "org.hibernate.annotations.JdbcType",
    "org.hibernate.annotations.Convert", "org.hibernate.annotations.CompositeType",
  )
  const val ATTRIBUTE_CONVERTER: String = "jakarta.persistence.AttributeConverter"
  private const val ATTRIBUTE_CONVERTER_OLD = "javax.persistence.AttributeConverter"

  fun fqns(simple: String): List<String> = listOf("jakarta.persistence.$simple", "javax.persistence.$simple")

  fun annotation(owner: UAnnotated, simple: String): UAnnotation? {
    val names = fqns(simple)
    return owner.uAnnotations.firstOrNull { it.qualifiedName in names }
  }

  fun has(owner: UAnnotated, vararg simple: String): Boolean = simple.any { annotation(owner, it) != null }

  fun hasOn(owner: PsiModifierListOwner, simple: String): Boolean = fqns(simple).any { owner.hasAnnotation(it) }

  fun relation(owner: UAnnotated): UAnnotation? = RELATIONS.firstNotNullOfOrNull { annotation(owner, it) }

  fun kind(annotation: UAnnotation): String = annotation.qualifiedName.orEmpty().substringAfterLast('.')

  /** The source text of an attribute value, e.g. `FetchType.LAZY` or `[CascadeType.ALL]`. */
  fun attributeText(annotation: UAnnotation, name: String): String? = annotation.findDeclaredAttributeValue(name)?.sourcePsi?.text

  fun isLazy(annotation: UAnnotation): Boolean {
    val fetch = attributeText(annotation, "fetch") ?: return kind(annotation) in TO_MANY
    return fetch.contains("LAZY")
  }

  fun cascadesRemoval(annotation: UAnnotation): Boolean =
    attributeText(annotation, "cascade")?.let { Regex("""\b(ALL|REMOVE)\b""").containsMatchIn(it) } == true

  fun mappedBy(annotation: UAnnotation): String? =
    annotation.findDeclaredAttributeValue("mappedBy")?.evaluateString()?.takeIf { it.isNotBlank() }

  /** Whether the entity maps its attributes through getters (`@Id` on a method) instead of fields. */
  fun usesPropertyAccess(psiClass: PsiClass): Boolean =
    psiClass.allMethods.any { m -> fqns("Id").any { m.hasAnnotation(it) } || fqns("EmbeddedId").any { m.hasAnnotation(it) } }

  fun hasTypeMapping(owner: UAnnotated): Boolean =
    TYPE_MAPPINGS.any { annotation(owner, it) != null } || owner.uAnnotations.any { it.qualifiedName in HIBERNATE_TYPE_MAPPINGS }

  fun isCollectionOrMap(type: PsiType): Boolean {
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(type) ?: return false
    return InheritanceUtil.isInheritor(psiClass, false, "java.util.Collection") || psiClass.qualifiedName == "java.util.Collection" ||
           InheritanceUtil.isInheritor(psiClass, false, "java.util.Map") || psiClass.qualifiedName == "java.util.Map"
  }

  fun implementsConverter(psiClass: PsiClass): Boolean =
    InheritanceUtil.isInheritor(psiClass, ATTRIBUTE_CONVERTER) || InheritanceUtil.isInheritor(psiClass, ATTRIBUTE_CONVERTER_OLD)

  /** Enum types a `@Converter(autoApply = true)` converts in this module, so their attributes need no `@Enumerated`. */
  fun autoAppliedTypes(module: Module): Set<String> = CachedValuesManager.getManager(module.project).getCachedValue(module) {
    val project = module.project
    val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
    val result = HashSet<String>()
    for (fqn in fqns("Converter")) {
      val annotationClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project)) ?: continue
      for (converter in AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()) {
        val annotation = converter.getAnnotation(fqn) ?: continue
        if (AnnotationUtil.getBooleanAttributeValue(annotation, "autoApply") != true) continue
        val type = JavaPsiFacade.getElementFactory(project).createType(converter)
        for (base in listOf(ATTRIBUTE_CONVERTER, ATTRIBUTE_CONVERTER_OLD)) {
          PsiUtil.substituteTypeParameter(type, base, 0, false)?.let { PsiUtil.resolveClassInClassTypeOnly(it)?.qualifiedName }?.let(result::add)
        }
      }
    }
    CachedValueProvider.Result.create(result, PsiModificationTracker.getInstance(project))
  }
}
