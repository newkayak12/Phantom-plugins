// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.exec.DbColumnInfo
import dev.phantom.db.core.exec.DbConnectionService
import dev.phantom.db.core.exec.DbMetadataCache
import dev.phantom.db.core.exec.DbTableRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A JPA entity for a table: one property per column with `@Column(name)`, `@Id` on the primary key (an `@IdClass`
 * is not generated: composite keys get `@Id` on each column), nullability from the column. Foreign keys stay plain
 * columns; turn them into `@ManyToOne` where the model needs navigation.
 */
object DbEntityGenerator {
  enum class Language(val extension: String) { KOTLIN("kt"), JAVA("java") }

  data class Property(val column: String, val name: String, val type: String, val imports: List<String>, val nullable: Boolean, val id: Boolean)

  fun className(table: String): String = words(table).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Entity" }

  fun propertyName(column: String): String = words(column).mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
    .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }.ifEmpty { "value" }

  private fun words(s: String): List<String> = s.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    .flatMap { w -> if (w.any(Char::isLowerCase) && w.any(Char::isUpperCase)) w.split(Regex("(?<=[a-z0-9])(?=[A-Z])")) else listOf(w) }
    .map { it.lowercase() }

  /** The Kotlin or Java type for a database type name (`VARCHAR(255)`, `bigint unsigned`, `timestamptz`). */
  fun javaType(typeName: String, language: Language): Pair<String, String?> {
    val t = typeName.uppercase().substringBefore('(').replace("UNSIGNED", "").trim()
    val kotlin = language == Language.KOTLIN
    fun prim(k: String, j: String) = (if (kotlin) k else j) to null
    return when (t) {
      "BIGINT", "INT8", "BIGSERIAL", "SERIAL8" -> prim("Long", "Long")
      "INT", "INTEGER", "INT4", "MEDIUMINT", "SERIAL", "SERIAL4" -> prim("Int", "Integer")
      "SMALLINT", "INT2", "SMALLSERIAL" -> prim("Short", "Short")
      "TINYINT" -> prim("Byte", "Byte")
      "BIT", "BOOL", "BOOLEAN" -> prim("Boolean", "Boolean")
      "DECIMAL", "NUMERIC", "NUMBER", "MONEY" -> "BigDecimal" to "java.math.BigDecimal"
      "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> prim("Double", "Double")
      "FLOAT", "REAL", "FLOAT4" -> prim("Float", "Float")
      "DATE" -> "LocalDate" to "java.time.LocalDate"
      "TIME", "TIME WITHOUT TIME ZONE" -> "LocalTime" to "java.time.LocalTime"
      "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> "LocalDateTime" to "java.time.LocalDateTime"
      "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE" -> "OffsetDateTime" to "java.time.OffsetDateTime"
      "UUID" -> "UUID" to "java.util.UUID"
      "BINARY", "VARBINARY", "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BYTEA", "BINARY LARGE OBJECT" -> (if (kotlin) "ByteArray" else "byte[]") to null
      else -> "String" to null
    }
  }

  fun properties(columns: List<DbColumnInfo>, language: Language): List<Property> = columns.map { c ->
    val (type, import) = javaType(c.typeName, language)
    Property(c.name, propertyName(c.name), type, listOfNotNull(import), c.nullable && !c.primaryKey, c.primaryKey)
  }

  fun source(language: Language, packageName: String, className: String, table: String, properties: List<Property>, jakarta: Boolean): String {
    val persistence = if (jakarta) "jakarta.persistence" else "javax.persistence"
    val imports = (listOf("$persistence.Column", "$persistence.Entity", "$persistence.Table") +
                   (if (properties.any { it.id }) listOf("$persistence.Id") else emptyList()) + properties.flatMap { it.imports }).distinct().sorted()
    return buildString {
      if (packageName.isNotEmpty()) append("package ").append(packageName).append(if (language == Language.JAVA) ";\n\n" else "\n\n")
      for (i in imports) append("import ").append(i).append(if (language == Language.JAVA) ";\n" else "\n")
      append("\n@Entity\n@Table(name = \"").append(table).append("\")\n")
      if (language == Language.KOTLIN) {
        append("class ").append(className).append("(\n")
        for (p in properties) {
          if (p.id) append("    @Id\n")
          append("    @Column(name = \"").append(p.column).append('"').append(if (!p.nullable) ", nullable = false" else "").append(")\n")
          append("    var ").append(p.name).append(": ").append(p.type).append(if (p.nullable) "? = null" else "").append(",\n\n")
        }
        if (properties.isNotEmpty()) setLength(length - 1)
        append(")\n")
      }
      else {
        append("public class ").append(className).append(" {\n")
        for (p in properties) {
          if (p.id) append("    @Id\n")
          append("    @Column(name = \"").append(p.column).append('"').append(if (!p.nullable) ", nullable = false" else "").append(")\n")
          append("    private ").append(p.type).append(' ').append(p.name).append(";\n\n")
        }
        append("    protected ").append(className).append("() {\n    }\n")
        for (p in properties) {
          val cap = p.name.replaceFirstChar(Char::uppercaseChar)
          append("\n    public ").append(p.type).append(" get").append(cap).append("() {\n        return ").append(p.name).append(";\n    }\n")
          append("\n    public void set").append(cap).append('(').append(p.type).append(' ').append(p.name).append(") {\n        this.")
            .append(p.name).append(" = ").append(p.name).append(";\n    }\n")
        }
        append("}\n")
      }
    }
  }

  /** jakarta.persistence when the project has it or has neither; javax.persistence for older projects. */
  fun usesJakarta(project: Project): Boolean {
    val roots = OrderEnumerator.orderEntries(project).librariesOnly().classes().roots.map { it.name }
    return roots.any { it.startsWith("jakarta.persistence") } || roots.none { it.startsWith("javax.persistence") || it.startsWith("hibernate-jpa") }
  }

  /** Reads the columns (from the cache or the database), asks where to put the class, writes it and opens it. */
  fun start(project: Project, dataSource: DbDataSource, table: DbTableRef) {
    DbConnectionService.getInstance(project).launch {
      val columns = runCatching { DbMetadataCache.getInstance(project).columns(dataSource, table) }
      withContext(Dispatchers.EDT) {
        columns.onFailure { Messages.showErrorDialog(project, it.message, "Generate Entity") }.onSuccess { cols ->
          val dialog = Dialog(project, table)
          if (dialog.showAndGet()) write(project, dialog, table, cols)
        }
      }
    }
  }

  private fun write(project: Project, dialog: Dialog, table: DbTableRef, columns: List<DbColumnInfo>) {
    val language = dialog.language
    val name = "${dialog.className}.${language.extension}"
    val jakarta = usesJakarta(project)
    val file = WriteCommandAction.writeCommandAction(project).withName("Generate Entity").compute<com.intellij.openapi.vfs.VirtualFile?, Exception> {
      val dir = VfsUtil.createDirectoryIfMissing(dialog.directory.path) ?: return@compute null
      if (dir.findChild(name) != null) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
          Messages.showErrorDialog(project, "$name already exists in ${dir.presentableUrl}", "Generate Entity")
        }
        return@compute null
      }
      val packageName = ProjectFileIndex.getInstance(project).getPackageNameByDirectory(dir).orEmpty()
      val text = source(language, packageName, dialog.className, table.name, properties(columns, language), jakarta)
      dir.createChildData(this, name).also { VfsUtil.saveText(it, text) }
    } ?: return
    FileEditorManager.getInstance(project).openFile(file, true)
  }

  private class Dialog(private val project: Project, table: DbTableRef) : DialogWrapper(project) {
    private val name = JBTextField(className(table.name))
    private val kotlin = JBRadioButton("Kotlin", true)
    private val java = JBRadioButton("Java")
    private val dir = TextFieldWithBrowseButton().apply {
      addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Directory for the Entity"))
      text = defaultDirectory()
    }

    init {
      title = "Generate JPA Entity for ${table.name}"
      ButtonGroup().apply { add(kotlin); add(java) }
      init()
    }

    val className: String get() = name.text.trim()
    val language: Language get() = if (kotlin.isSelected) Language.KOTLIN else Language.JAVA
    val directory: File get() = File(dir.text.trim())

    /** The first production source root, so the user only has to append the package. */
    private fun defaultDirectory(): String {
      val roots = ProjectRootManager.getInstance(project).contentSourceRoots.filter { !it.path.contains("/test/") && !it.path.contains("/resources") }
      return (roots.firstOrNull { it.path.endsWith("/kotlin") || it.path.endsWith("/java") } ?: roots.firstOrNull() ?: project.guessProjectDir())?.path.orEmpty()
    }

    override fun doValidate(): ValidationInfo? = when {
      !className.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) -> ValidationInfo("Not a class name", name)
      dir.text.isBlank() -> ValidationInfo("Choose a directory", dir.textField)
      LocalFileSystem.getInstance().findFileByIoFile(directory)?.let { ProjectFileIndex.getInstance(project).getPackageNameByDirectory(it) } == null &&
        directory.exists() -> ValidationInfo("Not inside a source root", dir.textField)
      else -> null
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
      .addLabeledComponent("Class name:", name)
      .addLabeledComponent("Language:", JPanel().apply { add(kotlin); add(java) })
      .addLabeledComponent("Directory:", dir)
      .panel
  }
}
