// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.jpa.core

import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil

/**
 * Spring Data query derivation from method names, following the "Query Creation" rules of the Spring Data reference:
 * `find…By` + predicate parts joined by `And`/`Or`, each a property path and an optional operator keyword, then an
 * optional `OrderBy` clause. Only the parsing and property resolution live here; no PSI is modified.
 */
object DerivedQuery {
  const val REPOSITORY: String = "org.springframework.data.repository.Repository"
  private val QUERY_ANNOTATIONS = listOf(
    "org.springframework.data.jpa.repository.Query",
    "org.springframework.data.jpa.repository.NativeQuery",
  )
  /** Parameters Spring passes alongside the query arguments. */
  private val SPECIAL_PARAMETERS = listOf(
    "org.springframework.data.domain.Pageable",
    "org.springframework.data.domain.Sort",
    "org.springframework.data.domain.Limit",
    "org.springframework.data.domain.ScrollPosition",
  )

  val PREFIX: Regex = Regex("^(find|read|get|query|search|stream|count|exists|delete|remove)((\\p{Lu}.*?))??By")

  /** Subjects offered while the user has not typed `By` yet. */
  val SUBJECTS: List<String> = listOf("findBy", "findAllBy", "findFirstBy", "findTop10By", "findDistinctBy", "existsBy", "countBy", "deleteBy", "streamBy")

  /** Operator keywords with the number of arguments they take; longer spellings first so suffix matching is greedy. */
  val OPERATORS: List<Pair<String, Int>> = listOf(
    "IsNotBetween" to 2, "NotBetween" to 2, "IsBetween" to 2, "Between" to 2,
    "IsNotNull" to 0, "NotNull" to 0, "IsNull" to 0, "Null" to 0,
    "IsLessThanEqual" to 1, "LessThanEqual" to 1, "IsLessThan" to 1, "LessThan" to 1,
    "IsGreaterThanEqual" to 1, "GreaterThanEqual" to 1, "IsGreaterThan" to 1, "GreaterThan" to 1,
    "IsBefore" to 1, "Before" to 1, "IsAfter" to 1, "After" to 1,
    "IsNotLike" to 1, "NotLike" to 1, "IsLike" to 1, "Like" to 1,
    "IsStartingWith" to 1, "StartingWith" to 1, "StartsWith" to 1,
    "IsEndingWith" to 1, "EndingWith" to 1, "EndsWith" to 1,
    "IsNotContaining" to 1, "NotContaining" to 1, "NotContains" to 1,
    "IsContaining" to 1, "Containing" to 1, "Contains" to 1,
    "IsNotEmpty" to 0, "NotEmpty" to 0, "IsEmpty" to 0, "Empty" to 0,
    "IsNotIn" to 1, "NotIn" to 1, "IsIn" to 1, "In" to 1,
    "IsTrue" to 0, "True" to 0, "IsFalse" to 0, "False" to 0,
    "MatchesRegex" to 1, "Matches" to 1, "Regex" to 1,
    "IsNear" to 1, "Near" to 1, "IsWithin" to 1, "Within" to 1,
    "Exists" to 0,
    "IsNot" to 1, "Not" to 1, "Equals" to 1, "Is" to 1,
  )

  /** The operators offered in completion: one spelling each. */
  val COMPLETION_OPERATORS: List<String> = listOf(
    "In", "NotIn", "IsNull", "IsNotNull", "Between", "LessThan", "LessThanEqual", "GreaterThan", "GreaterThanEqual",
    "Before", "After", "Like", "NotLike", "StartingWith", "EndingWith", "Containing", "Not", "IsTrue", "IsFalse",
    "IsEmpty", "IsNotEmpty", "IgnoreCase",
  )

  class Part(val source: String, val offset: Int, val propertySource: String, val operator: String?, val arguments: Int)

  class Parsed(val subjectEnd: Int, val parts: List<Part>, val orderBy: List<Part>)

  /** Splits a method name; null when it does not start like a derived query. Offsets are into [name]. */
  fun parse(name: String): Parsed? {
    val prefix = PREFIX.find(name) ?: return null
    val subjectEnd = prefix.range.last + 1
    var predicate = name.substring(subjectEnd)
    var orderSource: String? = null
    val orderIndex = Regex("OrderBy(?=\\p{Lu})").find(predicate)?.range?.first
    if (orderIndex != null) {
      orderSource = predicate.substring(orderIndex + "OrderBy".length)
      predicate = predicate.substring(0, orderIndex)
    }
    val allIgnoreCase = Regex("All(IgnoreCase|IgnoringCase)$").find(predicate)
    if (allIgnoreCase != null) predicate = predicate.substring(0, allIgnoreCase.range.first)

    val parts = ArrayList<Part>()
    var offset = subjectEnd
    for (orPart in splitKeeping(predicate, "Or")) {
      for (andPart in splitKeeping(orPart.first, "And")) {
        parts.add(predicatePart(andPart.first, offset + orPart.second + andPart.second))
      }
    }
    val orderBy = ArrayList<Part>()
    if (orderSource != null) {
      var orderOffset = subjectEnd + orderIndex!! + "OrderBy".length
      // `OrderByLastnameAscFirstnameDesc`: each property ends with Asc or Desc
      val matcher = Regex("(.+?)(Asc|Desc)(?=\\p{Lu}|$)").findAll(orderSource).toList()
      if (matcher.isEmpty()) orderBy.add(Part(orderSource, orderOffset, orderSource, null, 0))
      for (m in matcher) {
        orderBy.add(Part(m.value, orderOffset + m.range.first, m.groupValues[1], m.groupValues[2], 0))
      }
      orderOffset += orderSource.length
    }
    return Parsed(subjectEnd, parts, orderBy)
  }

  private fun predicatePart(source: String, offset: Int): Part {
    var rest = source
    Regex("(IgnoreCase|IgnoringCase)$").find(rest)?.let { rest = rest.substring(0, it.range.first) }
    for ((keyword, arguments) in OPERATORS) {
      if (rest.length > keyword.length && rest.endsWith(keyword) && rest[rest.length - keyword.length - 1].let { it.isLetterOrDigit() || it == '_' }) {
        return Part(source, offset, rest.substring(0, rest.length - keyword.length), keyword, arguments)
      }
    }
    return Part(source, offset, rest, null, 1)
  }

  /** Splits on [keyword] followed by an upper-case letter, with each piece's offset. */
  private fun splitKeeping(text: String, keyword: String): List<Pair<String, Int>> {
    val result = ArrayList<Pair<String, Int>>()
    var start = 0
    for (m in Regex("$keyword(?=\\p{Lu})").findAll(text)) {
      if (m.range.first == 0) continue
      result.add(text.substring(start, m.range.first) to start)
      start = m.range.last + 1
    }
    result.add(text.substring(start) to start)
    return result
  }

  // ---- properties

  /** The entity a repository interface manages: the first type argument of `Repository<T, ID>`. */
  fun entityOf(repository: PsiClass): PsiClass? {
    if (!InheritanceUtil.isInheritor(repository, REPOSITORY)) return null
    val type = JavaPsiFacade.getElementFactory(repository.project).createType(repository)
    return PsiUtil.substituteTypeParameter(type, REPOSITORY, 0, false)?.let { PsiUtil.resolveClassInClassTypeOnly(it) }
  }

  fun isDerivedQueryMethod(method: PsiMethod): Boolean =
    !method.hasModifierProperty(PsiModifier.DEFAULT) && !method.hasModifierProperty(PsiModifier.STATIC) &&
    method.body == null && QUERY_ANNOTATIONS.none { method.hasAnnotation(it) } && PREFIX.containsMatchIn(method.name)

  fun countsAsArgument(parameter: PsiParameter): Boolean {
    val psiClass = PsiUtil.resolveClassInClassTypeOnly(parameter.type) ?: return true
    return SPECIAL_PARAMETERS.none { InheritanceUtil.isInheritor(psiClass, false, it) || psiClass.qualifiedName == it }
  }

  /**
   * Resolves a property path the way Spring's PropertyPath does: the whole name first, then the longest leading
   * camel-case head that is a property, continuing into its type; `_` separates explicitly. Null when unresolvable.
   */
  fun resolvePath(entity: PsiClass, source: String): List<PsiMember>? {
    if (source.isEmpty()) return null
    if (source.contains('_')) {
      var owner: PsiClass? = entity
      val result = ArrayList<PsiMember>()
      for (segment in source.split('_').filter { it.isNotEmpty() }) {
        val member = resolvePath(owner ?: return null, segment) ?: return null
        result.addAll(member)
        owner = JpaModel.valueClass(member.last())
      }
      return result
    }
    JpaModel.attribute(entity, decapitalize(source))?.let { return listOf(it) }
    for (i in source.length - 1 downTo 1) {
      if (!source[i].isUpperCase()) continue
      val head = JpaModel.attribute(entity, decapitalize(source.substring(0, i))) ?: continue
      val next = JpaModel.valueClass(head) ?: continue
      val tail = resolvePath(next, source.substring(i)) ?: continue
      return listOf(head) + tail
    }
    return null
  }

  /** Property paths offered in completion: attributes and, one level down, attributes of embedded types and associations. */
  fun completionPaths(entity: PsiClass): List<String> {
    val result = LinkedHashSet<String>()
    for (field in JpaModel.attributes(entity)) {
      val head = capitalize(field.name)
      result.add(head)
      val next = JpaModel.valueClass(field) ?: continue
      if (!JpaModel.isPersistent(next)) continue
      for (nested in JpaModel.attributes(next)) result.add(head + capitalize(nested.name))
    }
    return result.toList()
  }

  fun decapitalize(s: String): String = if (s.length > 1 && s[1].isUpperCase() && s[0].isUpperCase()) s else s.replaceFirstChar { it.lowercaseChar() }

  fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }
}
