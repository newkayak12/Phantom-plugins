// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.springboot.core

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

/**
 * `spring.datasource.url: jdbc:mysql://...` while no MySQL driver is anywhere on the project's classpath: the
 * application fails at startup with "Cannot load driver class". The driver is looked up in every module and library
 * of the project (a `runtimeOnly` driver in a sibling module counts), so only a driver missing from the whole build is
 * reported. A `driver-class-name` in the same file is checked instead of the URL's vendor.
 */
class BootDatasourceDriverInspection : LocalInspectionTool() {
  override fun isAvailableForFile(file: PsiFile): Boolean = !GhostMode.enabled && super.isAvailableForFile(file)
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
    override fun visitFile(file: PsiFile) {
      if (!BootConfigFiles.isConfigFile(file)) return
      val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
      if (!SpringBootGate.isBootModule(module)) return
      val text = (PsiDocumentManager.getInstance(file.project).getDocument(file)?.charsSequence ?: file.viewProvider.contents).toString()
      val entries = if (file.name.endsWith(".properties")) ConfigText.properties(text) else ConfigText.yaml(text)
      val byKey = entries.associateBy { ConfigNames.normalize(it.key) }
      val explicitDriver = byKey[DRIVER_KEY]?.value?.takeIf { it.isNotBlank() && !it.contains("\${") }
      for (key in URL_KEYS) {
        val entry = byKey[key] ?: continue
        val url = entry.value
        if (!url.startsWith("jdbc:")) continue
        val drivers = if (explicitDriver != null) listOf(explicitDriver) else DRIVERS[vendorOf(url) ?: continue]?.first ?: continue
        val scope = GlobalSearchScope.allScope(file.project)
        if (drivers.any { JavaPsiFacade.getInstance(file.project).findClass(it, scope) != null }) continue
        val range = valueRange(text, entry.offset, url) ?: continue
        val hint = if (explicitDriver != null) explicitDriver else DRIVERS[vendorOf(url)]!!.second
        holder.registerProblem(file, PhantomSpringBootBundle.message("inspection.driver.missing", vendorOf(url) ?: "", hint),
                               ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
      }
    }
  }

  private fun vendorOf(url: String): String? = url.removePrefix("jdbc:").substringBefore(':').takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }

  /** The value's range on the key's line. */
  private fun valueRange(text: String, keyOffset: Int, value: String): TextRange? {
    val lineEnd = text.indexOf('\n', keyOffset).let { if (it < 0) text.length else it }
    val at = text.indexOf(value, keyOffset).takeIf { it in keyOffset until lineEnd } ?: return null
    return TextRange(at, at + value.length)
  }

  private companion object {
    val URL_KEYS = listOf("spring.datasource.url", "spring.datasource.hikari.jdbc-url")
    const val DRIVER_KEY = "spring.datasource.driver-class-name"

    /** Vendor in `jdbc:<vendor>:` to the driver classes that accept it and the dependency to add. */
    val DRIVERS: Map<String, Pair<List<String>, String>> = mapOf(
      "mysql" to (listOf("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver") to "com.mysql:mysql-connector-j"),
      "mariadb" to (listOf("org.mariadb.jdbc.Driver") to "org.mariadb.jdbc:mariadb-java-client"),
      "postgresql" to (listOf("org.postgresql.Driver") to "org.postgresql:postgresql"),
      "h2" to (listOf("org.h2.Driver") to "com.h2database:h2"),
      "hsqldb" to (listOf("org.hsqldb.jdbc.JDBCDriver", "org.hsqldb.jdbcDriver") to "org.hsqldb:hsqldb"),
      "sqlserver" to (listOf("com.microsoft.sqlserver.jdbc.SQLServerDriver") to "com.microsoft.sqlserver:mssql-jdbc"),
      "oracle" to (listOf("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver") to "com.oracle.database.jdbc:ojdbc11"),
      "sqlite" to (listOf("org.sqlite.JDBC") to "org.xerial:sqlite-jdbc"),
      "derby" to (listOf("org.apache.derby.jdbc.EmbeddedDriver", "org.apache.derby.iapi.jdbc.AutoloadedDriver") to "org.apache.derby:derby"),
    )
  }
}
