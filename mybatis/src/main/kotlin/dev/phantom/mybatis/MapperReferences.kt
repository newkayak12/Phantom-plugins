// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * References in mapper XML: `namespace` to the interface, statement `id` to its method, `resultMap` / `extends` /
 * `refid` to the tags they name (also `other.Mapper.id` in another mapper), types to classes and `property` to the
 * properties of the mapped type.
 */
class MapperReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(), MapperReferenceProvider())
  }
}

private class MapperReferenceProvider : PsiReferenceProvider() {
  override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
    val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
    val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
    val tag = attribute.parent ?: return PsiReference.EMPTY_ARRAY
    val root = MyBatis.mapperOf(tag) ?: return PsiReference.EMPTY_ARRAY
    if (value.value.isBlank()) return PsiReference.EMPTY_ARRAY
    return when (attribute.name) {
      "namespace" -> if (tag == root) classReferences(value, soft = false) else PsiReference.EMPTY_ARRAY
      "id" -> if (tag.parentTag == root && tag.name in MyBatis.STATEMENTS) arrayOf(StatementIdReference(value)) else PsiReference.EMPTY_ARRAY
      "resultType", "parameterType", "type", "javaType", "ofType" ->
        if (value.value.contains('.')) classReferences(value, soft = true) else PsiReference.EMPTY_ARRAY // aliases: int, map, ...
      "resultMap" -> arrayOf(TagIdReference(value, "resultMap"))
      "extends" -> if (tag.name == "resultMap") arrayOf(TagIdReference(value, "resultMap")) else PsiReference.EMPTY_ARRAY
      "refid" -> if (tag.name == "include") arrayOf(TagIdReference(value, "sql")) else PsiReference.EMPTY_ARRAY
      "property" -> if (MapperTypes.mappedClass(tag) != null) arrayOf(PropertyReference(value)) else PsiReference.EMPTY_ARRAY
      else -> PsiReference.EMPTY_ARRAY
    }
  }

  private fun classReferences(value: XmlAttributeValue, soft: Boolean): Array<PsiReference> {
    val provider = JavaClassReferenceProvider()
    provider.isSoft = soft
    return provider.getReferencesByElement(value)
  }
}

/** The class whose properties `property` names in a `<resultMap>`. */
object MapperTypes {
  fun mappedClass(tag: XmlTag): PsiClass? {
    var t: XmlTag? = tag.parentTag
    while (t != null) {
      val name = when (t.name) {
        "resultMap" -> t.getAttributeValue("type")
        "association" -> t.getAttributeValue("javaType")
        "collection" -> t.getAttributeValue("ofType")
        "constructor", "discriminator", "case" -> { t = t.parentTag; continue }
        else -> null
      } ?: return null
      return JavaPsiFacade.getInstance(tag.project).findClass(name.trim(), tag.resolveScope)
    }
    return null
  }
}

/** A statement id: the mapper method of that name. Renaming the method renames the id. */
class StatementIdReference(value: XmlAttributeValue) : PsiReferenceBase<XmlAttributeValue>(value, true) {
  override fun resolve(): PsiElement? {
    val tag = (element.parent as? XmlAttribute)?.parent ?: return null
    return MyBatis.methodsFor(tag).firstOrNull()
  }

  override fun getVariants(): Array<Any> {
    val file = element.containingFile
    val mapper = MyBatis.mapperClass(file) ?: return emptyArray()
    val taken = MyBatis.statements(file).mapNotNull { it.getAttributeValue("id") }.toSet()
    return mapper.allMethods.filter { MyBatis.needsStatement(it) && it.name !in taken && !MyBatis.hasAnnotatedSql(it) }
      .distinctBy { it.name }
      .map { LookupElementBuilder.create(it, it.name).withIcon(com.intellij.icons.AllIcons.Nodes.Method) }
      .toTypedArray()
  }
}

/** `resultMap="x"`, `extends="x"` and `refid="x"`: the `<resultMap>` / `<sql>` with that id, here or as `ns.x` elsewhere. */
class TagIdReference(value: XmlAttributeValue, private val kind: String) : PsiReferenceBase<XmlAttributeValue>(value, true) {
  override fun resolve(): PsiElement? = target()?.getAttribute("id")?.valueElement

  fun target(): XmlTag? = MyBatis.findTag(element, kind, element.value)

  override fun getVariants(): Array<Any> {
    val root = MyBatis.mapperRoot(element.containingFile) ?: return emptyArray()
    return root.subTags.filter { it.name == kind }.mapNotNull { it.getAttributeValue("id") }.map { LookupElementBuilder.create(it) }.toTypedArray()
  }
}

/** `property="a.b"` in a result map: the field or setter of the mapped type, segment by segment. */
class PropertyReference(value: XmlAttributeValue) : PsiReferenceBase<XmlAttributeValue>(value, lastSegment(value), true) {
  override fun resolve(): PsiElement? {
    val (owner, name) = ownerAndName() ?: return null
    return owner.findFieldByName(name, true) ?: PropertyUtilBase.findPropertySetter(owner, name, false, true)
  }

  override fun getVariants(): Array<Any> {
    val (owner, _) = ownerAndName() ?: return emptyArray()
    return MyBatis.properties(owner).map { (name, type) -> LookupElementBuilder.create(name).withTypeText(type.presentableText) }.toTypedArray()
  }

  private fun ownerAndName(): Pair<PsiClass, String>? {
    val tag = (element.parent as? XmlAttribute)?.parent ?: return null
    var owner = MapperTypes.mappedClass(tag) ?: return null
    val path = element.value.split('.')
    for (segment in path.dropLast(1)) owner = MyBatis.beanClass(MyBatis.properties(owner)[segment]) ?: return null
    return owner to path.last()
  }

  private companion object {
    fun lastSegment(value: XmlAttributeValue): TextRange {
      val range = ElementManipulators.getValueTextRange(value)
      val dot = value.value.lastIndexOf('.')
      return if (dot < 0) range else TextRange(range.startOffset + dot + 1, range.endOffset)
    }
  }
}
