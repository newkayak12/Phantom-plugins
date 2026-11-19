// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.jvm

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbDataSourceService
import dev.phantom.db.core.SqlNames
import dev.phantom.db.core.SqlSchema
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbTableRef
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UField
import org.jetbrains.uast.toUElement

/**
 * How Hibernate turns entity and attribute names into table and column names. Spring Boot's default physical strategy
 * (`CamelCaseToUnderscoresNamingStrategy`) puts `_` between a lower-case letter or digit and an upper-case letter
 * followed by a lower-case one, turns `.` into `_` and lower-cases the result; `PhysicalNamingStrategyStandardImpl`,
 * plain Hibernate's default, keeps names as written. Quoted names are always kept.
 */
internal class JpaNaming(val standard: Boolean, val implicitKnown: Boolean) {
  fun physical(logical: String): String {
    val unquoted = SqlNames.unquote(logical)
    if (unquoted != logical || standard) return unquoted
    val builder = StringBuilder(logical.replace('.', '_'))
    var i = 1
    while (i < builder.length - 1) {
      val before = builder[i - 1]
      val current = builder[i]
      val after = builder[i + 1]
      if ((before.isLowerCase() || before.isDigit()) && current.isUpperCase() && after.isLowerCase()) builder.insert(i++, '_')
      i++
    }
    return builder.toString().lowercase()
  }
}

/** What the project's Spring configuration says about the database: its JDBC URLs and the Hibernate naming strategies. */
internal class JpaSpringConfig(val urls: List<String>, val physical: String?, val physicalAmbiguous: Boolean, val implicit: String?) {
  companion object {
    private val JDBC_URL = Regex("""jdbc:[a-z0-9]+(?::[a-z0-9]+)*:[^\s"'`,}]+""", RegexOption.IGNORE_CASE)
    private val PHYSICAL = Regex("""(?:physical[-_]?strategy|physical_naming_strategy)\s*[:=]\s*["']?([\w.$]+)""", RegexOption.IGNORE_CASE)
    private val IMPLICIT = Regex("""(?:implicit[-_]?strategy|implicit_naming_strategy)\s*[:=]\s*["']?([\w.$]+)""", RegexOption.IGNORE_CASE)
    private val CONFIG_EXTENSIONS = setOf("yml", "yaml", "properties")
    private const val MAX_FILES = 300
    private const val MAX_BYTES = 256 * 1024

    fun get(project: Project): JpaSpringConfig = CachedValuesManager.getManager(project).getCachedValue(project) {
      CachedValueProvider.Result.create(compute(project), PsiModificationTracker.MODIFICATION_COUNT, ProjectRootManager.getInstance(project))
    }

    private fun compute(project: Project): JpaSpringConfig {
      val urls = LinkedHashSet<String>()
      val physical = LinkedHashSet<String>()
      val implicit = LinkedHashSet<String>()
      var files = 0
      for (module in ModuleManager.getInstance(project).modules) {
        for (folder in ModuleRootManager.getInstance(module).contentEntries.flatMap { it.sourceFolders.toList() }) {
          val root = folder.file ?: continue
          VfsUtilCore.iterateChildrenRecursively(root, { it.isDirectory || it.extension?.lowercase() in CONFIG_EXTENSIONS }) { file ->
            if (file.isDirectory || file.length > MAX_BYTES) return@iterateChildrenRecursively true
            val text = text(file) ?: return@iterateChildrenRecursively true
            JDBC_URL.findAll(text).mapTo(urls) { it.value.trimEnd('}', ')', ']') }
            // the naming the application runs with: test resources may override it for tests only
            if (!folder.isTestSource) {
              PHYSICAL.findAll(text).mapTo(physical) { it.groupValues[1] }
              IMPLICIT.findAll(text).mapTo(implicit) { it.groupValues[1] }
            }
            ++files < MAX_FILES
          }
        }
      }
      return JpaSpringConfig(urls.toList(), physical.singleOrNull(), physical.size > 1, implicit.singleOrNull() ?: implicit.firstOrNull())
    }

    private fun text(file: VirtualFile): String? =
      FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()

    /** `jdbc:mysql://127.0.0.1:3306/app?x=y` → `mysql://localhost:3306/app`, so a data source and the config compare. */
    fun key(url: String): String {
      var u = url.trim().lowercase().removePrefix("jdbc:").removePrefix("p6spy:")
      u = u.substringBefore('?').substringBefore(';')
      val scheme = u.substringBefore("://", "")
      if (scheme.isEmpty()) return u
      val rest = u.substringAfter("://")
      val authority = rest.substringBefore('/').substringAfter('@')
      val path = rest.substringAfter('/', "").trimEnd('/')
      val normalizedScheme = if (scheme == "mariadb") "mysql" else scheme
      var host = authority.substringBefore(':').ifEmpty { "localhost" }
      if (host == "127.0.0.1" || host == "0.0.0.0" || host == "[::1]") host = "localhost"
      val port = authority.substringAfter(':', "").ifEmpty {
        when (normalizedScheme) { "mysql" -> "3306"; "postgresql" -> "5432"; else -> "" }
      }
      return "$normalizedScheme://$host:$port/$path"
    }

    /** Databases that live only while the application runs: their schema is not the one a data source shows. */
    fun isInMemory(url: String): Boolean {
      val u = url.lowercase()
      return u.startsWith("jdbc:h2:mem:") || u.startsWith("jdbc:hsqldb:mem:") || u.startsWith("jdbc:derby:memory:") || u.startsWith("jdbc:tc:")
    }
  }
}

/** The JPA side of the schema check: the data source the entities map to, and the table and columns each entity uses. */
internal object JpaSchemaModel {
  private val ENTITY = listOf("jakarta.persistence.Entity", "javax.persistence.Entity")
  private val MAPPED_SUPERCLASS = listOf("jakarta.persistence.MappedSuperclass", "javax.persistence.MappedSuperclass")
  private val EMBEDDABLE = listOf("jakarta.persistence.Embeddable", "javax.persistence.Embeddable")
  private val EMBEDDED = listOf("jakarta.persistence.Embedded", "javax.persistence.Embedded", "jakarta.persistence.EmbeddedId", "javax.persistence.EmbeddedId")
  private val TABLE = listOf("jakarta.persistence.Table", "javax.persistence.Table")
  private val COLUMN = listOf("jakarta.persistence.Column", "javax.persistence.Column")
  private val JOIN_COLUMN = listOf("jakarta.persistence.JoinColumn", "javax.persistence.JoinColumn")
  private val JOIN_COLUMNS = listOf("jakarta.persistence.JoinColumns", "javax.persistence.JoinColumns")
  private val TO_ONE = listOf("jakarta.persistence.ManyToOne", "javax.persistence.ManyToOne", "jakarta.persistence.OneToOne", "javax.persistence.OneToOne")
  private val ID = listOf("jakarta.persistence.Id", "javax.persistence.Id")
  private val ACCESS = listOf("jakarta.persistence.Access", "javax.persistence.Access")
  private val INHERITANCE = listOf("jakarta.persistence.Inheritance", "javax.persistence.Inheritance")
  private val ATTRIBUTE_OVERRIDE = listOf("jakarta.persistence.AttributeOverride", "javax.persistence.AttributeOverride")
  private val ATTRIBUTE_OVERRIDES = listOf("jakarta.persistence.AttributeOverrides", "javax.persistence.AttributeOverrides")
  /** Attributes whose columns are elsewhere (another table, a join table) or computed. */
  private val NOT_HERE = listOf(
    "jakarta.persistence.Transient", "javax.persistence.Transient", "org.springframework.data.annotation.Transient",
    // Kotlin's @Transient makes the JVM field transient, which Hibernate does not map
    "kotlin.jvm.Transient",
    "jakarta.persistence.OneToMany", "javax.persistence.OneToMany", "jakarta.persistence.ManyToMany", "javax.persistence.ManyToMany",
    "jakarta.persistence.ElementCollection", "javax.persistence.ElementCollection", "jakarta.persistence.JoinTable", "javax.persistence.JoinTable",
    "jakarta.persistence.MapsId", "javax.persistence.MapsId", "jakarta.persistence.PrimaryKeyJoinColumn", "javax.persistence.PrimaryKeyJoinColumn",
    "org.hibernate.annotations.Formula", "org.hibernate.annotations.JoinFormula",
  )
  private const val BOOT = "org.springframework.boot.autoconfigure.SpringBootApplication"
  private val STANDARD_PHYSICAL = setOf("PhysicalNamingStrategyStandardImpl")
  private val SNAKE_PHYSICAL = setOf("CamelCaseToUnderscoresNamingStrategy", "SpringPhysicalNamingStrategy")
  private val DEFAULT_IMPLICIT = setOf("SpringImplicitNamingStrategy", "ImplicitNamingStrategyJpaCompliantImpl", "ImplicitNamingStrategyLegacyJpaImpl")

  fun isJpaProject(project: Project): Boolean = CachedValuesManager.getManager(project).getCachedValue(project) {
    val facade = JavaPsiFacade.getInstance(project)
    val scope = GlobalSearchScope.allScope(project)
    CachedValueProvider.Result.create(ENTITY.any { facade.findClass(it, scope) != null }, ProjectRootManager.getInstance(project))
  }

  fun isEntity(psiClass: PsiClass): Boolean = ENTITY.any { psiClass.hasAnnotation(it) }

  /**
   * The data source the entities are checked against: the one whose URL the configuration names, or the only one when
   * the configuration names none. Null when there is none, or it cannot be told which (several match, or the
   * configuration names a database no data source points to).
   */
  fun dataSource(project: Project): DbDataSource? {
    val all = DbDataSourceService.getInstance(project).dataSources
    if (all.isEmpty()) return null
    val urls = JpaSpringConfig.get(project).urls.filterNot(JpaSpringConfig::isInMemory).map(JpaSpringConfig::key).toSet()
    if (urls.isEmpty()) return all.singleOrNull()
    return all.filter { JpaSpringConfig.key(it.url) in urls }.singleOrNull()
  }

  /** The naming in effect, or null when the project sets a strategy this does not know (names cannot be predicted). */
  fun naming(project: Project): JpaNaming? {
    val config = JpaSpringConfig.get(project)
    if (config.physicalAmbiguous) return null
    val physical = config.physical?.substringAfterLast('.')
    val boot = JavaPsiFacade.getInstance(project).findClass(BOOT, GlobalSearchScope.allScope(project)) != null
    val standard = when {
      physical == null -> !boot
      physical in STANDARD_PHYSICAL -> true
      physical in SNAKE_PHYSICAL -> false
      else -> return null
    }
    val implicit = config.implicit?.substringAfterLast('.')
    return JpaNaming(standard, implicit == null || implicit in DEFAULT_IMPLICIT)
  }

  class TableName(val name: String, val schema: String?, val anchor: PsiElement?)

  /** A column an attribute maps to. [owner] is the class that declares the attribute, [path] its attribute path. */
  class ColumnUse(val column: String, val field: PsiField, val owner: PsiClass, val path: String, val anchor: PsiElement?, val nullableInCode: Boolean?)

  /** The entity whose table [entity] lives in: itself, or its root entity in SINGLE_TABLE inheritance. */
  fun tableOwner(entity: PsiClass): PsiClass {
    val ancestors = generateSequence(entity.superClass) { it.superClass }.filter(::isEntity).toList()
    val root = ancestors.lastOrNull() ?: return entity
    val strategy = INHERITANCE.firstNotNullOfOrNull { root.getAnnotation(it) }?.let { enumValue(it, "strategy") }
    return if (strategy == null || strategy == "SINGLE_TABLE") root else entity
  }

  fun table(entity: PsiClass, naming: JpaNaming): TableName {
    val owner = tableOwner(entity)
    val table = TABLE.firstNotNullOfOrNull { owner.getAnnotation(it) }
    val explicit = table?.let { string(it, "name") }?.takeIf { it.isNotBlank() }
    val logical = explicit ?: entityName(owner)
    val schema = table?.let { string(it, "schema")?.takeIf(String::isNotBlank) ?: string(it, "catalog")?.takeIf(String::isNotBlank) }
    val anchor = if (owner == entity) (table?.findDeclaredAttributeValue("name")?.takeIf { explicit != null } ?: table) else null
    return TableName(naming.physical(logical), schema?.let(SqlNames::unquote), anchor)
  }

  private fun entityName(psiClass: PsiClass): String =
    ENTITY.firstNotNullOfOrNull { psiClass.getAnnotation(it) }?.let { string(it, "name") }?.takeIf { it.isNotBlank() } ?: psiClass.name.orEmpty()

  /**
   * The columns [entity] maps in its table: attributes it declares, and those of its mapped superclasses when it
   * is the root of its hierarchy. Null when the mapping cannot be followed (property access).
   */
  fun columns(entity: PsiClass, naming: JpaNaming): List<ColumnUse>? {
    val hierarchy = generateSequence(entity) { it.superClass }.takeWhile { it.qualifiedName != "java.lang.Object" }.toList()
    if (hierarchy.any(::usesPropertyAccess)) return null
    val own = ArrayList<PsiClass>()
    own += entity
    // mapped superclasses up to the next entity: their attributes land in this entity's table
    for (superClass in hierarchy.drop(1)) {
      if (isEntity(superClass)) break
      if (MAPPED_SUPERCLASS.any { superClass.hasAnnotation(it) }) own += superClass
    }
    val result = ArrayList<ColumnUse>()
    for (owner in own) {
      for (field in owner.fields) {
        if (!isMapped(field)) continue
        if (!collect(field, null, owner, field.name, naming, emptyMap(), result, 0)) return null
      }
    }
    return result
  }

  /**
   * Adds the columns of [field] to [out]. Attributes of an embeddable are expanded; their columns are reported on [top],
   * the attribute of the class being checked. False when the mapping cannot be followed.
   */
  private fun collect(field: PsiField, top: PsiField?, owner: PsiClass, path: String, naming: JpaNaming, overrides: Map<String, String>,
                      out: MutableList<ColumnUse>, depth: Int): Boolean {
    val anchorField = top ?: field
    val type = (field.type as? PsiClassType)?.resolve()
    if (EMBEDDED.any { field.hasAnnotation(it) } || type != null && EMBEDDABLE.any { type.hasAnnotation(it) }) {
      if (type == null || depth > 2) return true
      val nested = HashMap(overrides.filterKeys { it.contains('.') }.mapKeys { it.key })
      // overrides on this attribute name its embeddable's attributes; deeper ones ("address.city") pass down
      val here = overrides.filterKeys { !it.contains('.') } + attributeOverrides(field).toMap()
      for (inner in type.allFields) {
        if (!isMapped(inner)) continue
        val innerOverrides = (here + nested).filterKeys { it.startsWith(inner.name + ".") }.mapKeys { it.key.removePrefix(inner.name + ".") }
        val override = here[inner.name]
        if (override != null) {
          out += ColumnUse(naming.physical(override), anchorField, owner, "$path.${inner.name}", anchorField, nullableInCode(inner))
          continue
        }
        if (!collect(inner, anchorField, owner, "$path.${inner.name}", naming, innerOverrides, out, depth + 1)) return false
      }
      return true
    }
    if (TO_ONE.any { annotation -> field.getAnnotation(annotation)?.let { string(it, "mappedBy").isNullOrBlank() } == true }) {
      val joins = JOIN_COLUMNS.firstNotNullOfOrNull { field.getAnnotation(it) }
        ?.let { AnnotationUtil.arrayAttributeValues(it.findDeclaredAttributeValue("value")).filterIsInstance<PsiAnnotation>() }
        ?: JOIN_COLUMN.mapNotNull { field.getAnnotation(it) }
      if (joins.any { !string(it, "table").isNullOrBlank() }) return true
      val named = joins.mapNotNull { join -> string(join, "name")?.takeIf(String::isNotBlank)?.let { it to join } }
      if (named.isNotEmpty()) {
        for ((name, join) in named) {
          val anchor = if (top == null) join.findDeclaredAttributeValue("name") ?: field else top
          out += ColumnUse(naming.physical(name), anchorField, owner, path, anchor, null)
        }
        return true
      }
      if (joins.size > 1 || !naming.implicitKnown) return true
      // implicit: <attribute>_<referenced column>
      val referenced = joins.firstOrNull()?.let { string(it, "referencedColumnName") }?.takeIf(String::isNotBlank)
                       ?: type?.let(::idColumn) ?: return true
      out += ColumnUse(naming.physical(field.name + "_" + SqlNames.unquote(referenced)), anchorField, owner, path, anchorField, null)
      return true
    }
    if (type != null && (isA(type, "java.util.Collection") || isA(type, "java.util.Map"))) return true
    val column = COLUMN.firstNotNullOfOrNull { field.getAnnotation(it) }
    if (column != null && !string(column, "table").isNullOrBlank()) return true
    val explicit = column?.let { string(it, "name") }?.takeIf(String::isNotBlank)
    val logical = overrides[field.name] ?: explicit ?: field.name
    val anchor = if (top == null && explicit != null && overrides[field.name] == null) column.findDeclaredAttributeValue("name") else anchorField
    out += ColumnUse(naming.physical(logical), anchorField, owner, path, anchor, nullableInCode(field))
    return true
  }

  private fun isA(psiClass: PsiClass, fqn: String): Boolean = psiClass.qualifiedName == fqn || InheritanceUtil.isInheritor(psiClass, fqn)

  /** The logical name of [entity]'s identifier column, or null for composite or missing identifiers. */
  private fun idColumn(entity: PsiClass): String? {
    val ids = entity.allFields.filter { field -> ID.any { field.hasAnnotation(it) } }
    val id = ids.singleOrNull() ?: return null
    return COLUMN.firstNotNullOfOrNull { id.getAnnotation(it) }?.let { string(it, "name") }?.takeIf(String::isNotBlank) ?: id.name
  }

  private fun attributeOverrides(field: PsiField): List<Pair<String, String>> {
    val annotations = ATTRIBUTE_OVERRIDE.mapNotNull { field.getAnnotation(it) } +
                      ATTRIBUTE_OVERRIDES.mapNotNull { field.getAnnotation(it) }.flatMap {
                        AnnotationUtil.arrayAttributeValues(it.findDeclaredAttributeValue("value")).filterIsInstance<PsiAnnotation>()
                      }
    return annotations.mapNotNull { override ->
      val name = string(override, "name") ?: return@mapNotNull null
      val column = override.findDeclaredAttributeValue("column") as? PsiAnnotation ?: return@mapNotNull null
      val columnName = string(column, "name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      name to columnName
    }
  }

  private fun isMapped(field: PsiField): Boolean {
    if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) return false
    if (field.name.contains('$')) return false
    if (NOT_HERE.any { field.hasAnnotation(it) }) return false
    // a Kotlin light field may not show every annotation of the declaration
    if (sourceAnnotations(field).any { it in NOT_HERE }) return false
    return !kotlinTransient(field)
  }

  private val KOTLIN_TRANSIENT = Regex("""@(?:field:|get:)?(?:kotlin\.jvm\.|jakarta\.persistence\.|javax\.persistence\.)?Transient\b""")
  private val VAL_OR_VAR = Regex("""\b(?:val|var)\b""")

  /**
   * `@Transient` written on a Kotlin property: Kotlin's own (the JVM `transient` modifier, which light fields do not
   * show) or JPA's. Read from the declaration's text before `val`/`var`, so it holds even when the name does not resolve.
   */
  private fun kotlinTransient(field: PsiField): Boolean {
    if (field.language.id == "JAVA") return false
    val declaration = field.navigationElement.takeIf { it != field } ?: return false
    val text = declaration.text
    val header = VAL_OR_VAR.find(text)?.let { text.substring(0, it.range.first) } ?: return false
    return KOTLIN_TRANSIENT.containsMatchIn(header)
  }

  private fun usesPropertyAccess(psiClass: PsiClass): Boolean {
    val access = ACCESS.firstNotNullOfOrNull { psiClass.getAnnotation(it) }?.let { enumValue(it, "value") }
    if (access == "PROPERTY") return true
    return psiClass.methods.any { method -> ID.any { method.hasAnnotation(it) } }
  }

  /**
   * Whether the attribute can hold null: false for primitives and Kotlin non-null types (their light fields carry
   * `@NotNull`), null when the code does not say (a Java reference type).
   */
  private fun nullableInCode(field: PsiField): Boolean? = when {
    field.type is PsiPrimitiveType -> false
    field.hasAnnotation(NOT_NULL) -> false
    field.hasAnnotation(NULLABLE) -> true
    else -> {
      // Kotlin: a light field may lack the annotation (lateinit); the UAST of the declaration states its nullability
      val names = sourceAnnotations(field)
      when {
        NOT_NULL in names -> false
        NULLABLE in names -> true
        else -> null
      }
    }
  }

  /** The annotations UAST sees on [field]'s declaration (for Kotlin: the property, including nullability). */
  private fun sourceAnnotations(field: PsiField): List<String> {
    if (field.language.id == "JAVA") return emptyList()
    val elements = listOfNotNull(field.toUElement(UField::class.java), field.navigationElement.takeIf { it != field }?.toUElement())
    return elements.filterIsInstance<UAnnotated>().flatMap { it.uAnnotations }.mapNotNull { it.qualifiedName }
  }

  private const val NOT_NULL = "org.jetbrains.annotations.NotNull"
  private const val NULLABLE = "org.jetbrains.annotations.Nullable"

  fun isId(field: PsiField): Boolean = ID.any { field.hasAnnotation(it) } || EMBEDDED.drop(2).any { field.hasAnnotation(it) }

  fun string(annotation: PsiAnnotation, name: String): String? {
    val value = annotation.findDeclaredAttributeValue(name) ?: return null
    return AnnotationUtil.getStringAttributeValue(value) ?: (value as? PsiLiteralValue)?.value as? String
  }

  private fun enumValue(annotation: PsiAnnotation, name: String): String? {
    val value = annotation.findDeclaredAttributeValue(name) ?: return null
    return (value as? PsiReferenceExpression)?.referenceName ?: value.text.substringAfterLast('.').trim()
  }

  /** The table in the schema an entity maps to; null while the schema is not loaded or when [table] names no schema it knows. */
  class Lookup(val dataSource: DbDataSource, val tables: List<DbTableRef>, val schemaName: String?)

  fun lookup(project: Project, dataSource: DbDataSource, table: TableName, load: Boolean): Lookup? {
    val schema = SqlSchema.getInstance(project)
    var snapshot = schema.snapshot(dataSource, load) ?: return null
    val schemaName = table.schema ?: snapshot.current?.name
    if (table.schema != null) {
      val ref = snapshot.schema(table.schema) ?: return null
      snapshot = schema.withSchema(dataSource, ref, load) ?: return null
    }
    val tables = if (schemaName == null) snapshot.find(table.name, null) else snapshot.find(table.name, schemaName)
    return Lookup(dataSource, tables, schemaName)
  }

  fun columnsOf(project: Project, dataSource: DbDataSource, table: DbTableRef, load: Boolean): List<DbColumnInfo>? =
    SqlSchema.getInstance(project).columns(dataSource, table, load)
}
