// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.mybatis

import com.intellij.DynamicBundle
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.PhantomMyBatisBundle"

object PhantomMyBatisBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Mapper XML files by their `<mapper namespace="...">`, read from the text so it needs no PSI. */
class MapperNamespaceIndex : ScalarIndexExtension<String>() {
  override fun getName(): ID<String, Void> = NAME
  override fun getVersion(): Int = 1
  override fun dependsOnFileContent(): Boolean = true
  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
  override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)

  override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
    val text = content.contentAsText
    if (!text.contains("<mapper")) return@DataIndexer emptyMap()
    val namespace = NAMESPACE.find(text)?.groupValues?.get(1)?.trim()
    if (namespace.isNullOrEmpty()) emptyMap() else mapOf(namespace to null)
  }

  companion object {
    val NAME: ID<String, Void> = ID.create("dev.phantom.mybatis.namespace")
    private val NAMESPACE = Regex("<mapper\\b[^>]*?\\bnamespace\\s*=\\s*[\"']([^\"']+)[\"']")
  }
}

/** What a MyBatis mapper is made of: the interface, its XML file(s) and the statements in them. */
object MyBatis {
  val STATEMENTS = setOf("select", "insert", "update", "delete")
  private const val ANNOTATIONS = "org.apache.ibatis.annotations."
  val SQL_ANNOTATIONS = listOf("Select", "Insert", "Update", "Delete").map { ANNOTATIONS + it }
  private val STATEMENT_ANNOTATIONS = SQL_ANNOTATIONS + listOf("SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider").map { ANNOTATIONS + it }
  const val PARAM = ANNOTATIONS + "Param"
  private const val MAPPER = ANNOTATIONS + "Mapper"

  /** The `<mapper>` root of a mapper XML file. */
  fun mapperRoot(file: PsiFile?): XmlTag? =
    (file as? XmlFile)?.rootTag?.takeIf { it.name == "mapper" && !it.getAttributeValue("namespace").isNullOrBlank() }

  fun namespace(file: PsiFile?): String? = mapperRoot(file)?.getAttributeValue("namespace")?.trim()

  /** The `<mapper>` root a tag is in, for tags of a mapper file only. */
  fun mapperOf(tag: XmlTag): XmlTag? = mapperRoot(tag.containingFile)

  /** The mapper XML files whose namespace is [namespace]. */
  fun mapperFiles(project: Project, namespace: String): List<XmlFile> {
    val manager = PsiManager.getInstance(project)
    return FileBasedIndex.getInstance().getContainingFiles(MapperNamespaceIndex.NAME, namespace, GlobalSearchScope.projectScope(project))
      .mapNotNull { manager.findFile(it) as? XmlFile }
      .filter { namespace(it) == namespace }
  }

  fun mapperFiles(mapper: PsiClass): List<XmlFile> = mapper.qualifiedName?.let { mapperFiles(mapper.project, it) }.orEmpty()

  /** The interface named by the mapper's namespace. */
  fun mapperClass(file: PsiFile): PsiClass? {
    val namespace = namespace(file) ?: return null
    return JavaPsiFacade.getInstance(file.project).findClass(namespace, file.resolveScope)
  }

  fun statements(file: PsiFile): List<XmlTag> = mapperRoot(file)?.subTags?.filter { it.name in STATEMENTS }.orEmpty()

  /** `<select>` and the others, `<sql>` and `<resultMap>` with [id] in the mapper file of [root]. */
  fun tagsById(root: XmlTag, names: Set<String>, id: String): List<XmlTag> =
    root.subTags.filter { it.name in names && it.getAttributeValue("id") == id }

  /** The XML statements of a mapper method, from the XML files of the interface it is declared in. */
  fun statementsFor(method: PsiMethod): List<XmlTag> {
    val mapper = method.containingClass ?: return emptyList()
    val name = method.name
    return mapperFiles(mapper).flatMap { file -> statements(file).filter { it.getAttributeValue("id") == name } }
  }

  /** The methods an XML statement is for: the namespace interface's (or its super-interfaces') methods with its id. */
  fun methodsFor(statement: XmlTag): List<PsiMethod> {
    val id = statement.getAttributeValue("id") ?: return emptyList()
    val mapper = mapperClass(statement.containingFile) ?: return emptyList()
    return mapper.findMethodsByName(id, true).toList()
  }

  /** A mapper interface: it has a mapper XML or is annotated @Mapper. */
  fun isMapper(cls: PsiClass): Boolean =
    cls.isInterface && !cls.isAnnotationType && (cls.hasAnnotation(MAPPER) || mapperFiles(cls).isNotEmpty())

  /** The method carries its own SQL (@Select, @SelectProvider, ...). */
  fun hasAnnotatedSql(method: PsiMethod): Boolean = STATEMENT_ANNOTATIONS.any { method.hasAnnotation(it) }

  /** An abstract method MyBatis binds to a statement (not default, static or Object's). */
  fun needsStatement(method: PsiMethod): Boolean =
    !method.isConstructor && method.hasModifierProperty(PsiModifier.ABSTRACT) && method.body == null &&
    !method.hasModifierProperty(PsiModifier.STATIC) && !method.hasModifierProperty(PsiModifier.DEFAULT)

  /** The statement tag (`<select>`...) or annotated method around [element]: the method whose parameters `#{}` names. */
  fun methodAround(element: PsiElement): PsiMethod? {
    PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.let { return it }
    var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
    while (tag != null && !(tag.name in STATEMENTS && tag.parentTag?.name == "mapper")) tag = tag.parentTag
    return tag?.let { methodsFor(it).firstOrNull() }
  }

  /** The name `#{}` uses for a parameter: its @Param value, else the parameter name. */
  fun parameterName(p: PsiParameter): String =
    p.getAnnotation(PARAM)?.findAttributeValue("value")?.let { v ->
      JavaPsiFacade.getInstance(p.project).constantEvaluationHelper.computeConstantExpression(v) as? String
    } ?: p.name

  /** A single parameter without @Param whose properties `#{}` names directly. */
  fun beanParameter(method: PsiMethod): PsiClass? {
    val p = method.parameterList.parameters.singleOrNull() ?: return null
    if (p.hasAnnotation(PARAM)) return null
    return beanClass(p.type)
  }

  /** The class whose properties a value of [type] has; null for simple values, collections and maps. */
  fun beanClass(type: PsiType?): PsiClass? {
    if (type == null || type is PsiPrimitiveType) return null
    val cls = PsiUtil.resolveClassInClassTypeOnly(type) ?: return null
    val name = cls.qualifiedName ?: return null
    if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("kotlin.")) return null
    if (InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_UTIL_COLLECTION) ||
        InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_UTIL_MAP)) return null
    return cls
  }

  /** Readable properties of a bean: fields and getters, by name. */
  fun properties(cls: PsiClass): Map<String, PsiType> {
    val result = LinkedHashMap<String, PsiType>()
    for (field in cls.allFields) if (!field.hasModifierProperty(PsiModifier.STATIC)) result.putIfAbsent(field.name, field.type)
    for (method in cls.allMethods) {
      if (method.hasModifierProperty(PsiModifier.STATIC) || method.containingClass?.qualifiedName == CommonClassNames.JAVA_LANG_OBJECT) continue
      if (!PropertyUtilBase.isSimplePropertyGetter(method)) continue
      val name = PropertyUtilBase.getPropertyNameByGetter(method)
      method.returnType?.let { result.putIfAbsent(name, it) }
    }
    return result
  }

  /** The element type of a collection, array or Optional; the type itself otherwise. */
  fun elementType(type: PsiType?): PsiType? {
    if (type is com.intellij.psi.PsiArrayType) return type.componentType
    val classType = type as? PsiClassType ?: return type
    val cls = classType.resolve() ?: return type
    if (InheritanceUtil.isInheritor(cls, CommonClassNames.JAVA_LANG_ITERABLE) || cls.qualifiedName == "java.util.Optional") {
      return classType.parameters.firstOrNull() ?: type
    }
    return type
  }
}
