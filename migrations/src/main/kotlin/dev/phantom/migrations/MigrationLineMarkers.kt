// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.migrations

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import javax.swing.Icon

/**
 * On the first line of a Flyway versioned migration: its version, and the migrations just before and after it in the
 * module's locations. Registered for every language (a `.sql` file may be plain text), so it returns early for
 * anything but the first leaf of a `.sql` file.
 */
class FlywayLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomMigrationsBundle.message("flyway.gutter.name")

  override fun getIcon(): Icon = AllIcons.Vcs.History

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.textRange.startOffset != 0 || element.firstChild != null || element.textLength == 0) return null
    val file = element.containingFile ?: return null
    val vFile = file.virtualFile ?: return null
    if (vFile.extension != "sql") return null
    val name = FlywayName.parse(vFile.name)?.takeIf { it.prefix == "V" } ?: return null
    val module = moduleOf(file) ?: return null
    if (!MigrationsGate.uses(module, "flyway-core")) return null
    if (FlywayLocations.locationOf(module, vFile) == null) return null
    val versioned = FlywayMigrations.of(module).filter { it.second.prefix == "V" && it.first != vFile }
    val previous = versioned.filter { compare(it.second.key, name.key) < 0 }.maxWithOrNull { a, b -> compare(a.second.key, b.second.key) }
    val next = versioned.filter { compare(it.second.key, name.key) > 0 }.minWithOrNull { a, b -> compare(a.second.key, b.second.key) }
    val targets = listOfNotNull(previous?.first, next?.first)
    if (targets.isEmpty()) return null
    val manager = PsiManager.getInstance(file.project)
    return NavigationGutterIconBuilder.create(AllIcons.Vcs.History)
      .setTargets(targets.mapNotNull { manager.findFile(it) })
      .setTooltipText(PhantomMigrationsBundle.message("flyway.gutter.tooltip", name.version.replace('_', '.'),
                                                      previous?.first?.name ?: "—", next?.first?.name ?: "—"))
      .setPopupTitle(PhantomMigrationsBundle.message("flyway.gutter.popup"))
      .createLineMarkerInfo(element)
  }

  private fun compare(a: List<java.math.BigInteger>, b: List<java.math.BigInteger>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
      val c = (a.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(b.getOrNull(i) ?: java.math.BigInteger.ZERO)
      if (c != 0) return c
    }
    return 0
  }
}

/** On a Liquibase `include` / `includeAll` (XML or YAML changelog): the changelog file or folder it pulls in. */
class LiquibaseIncludeLineMarkerProvider : LineMarkerProviderDescriptor() {
  override fun getName(): String = PhantomMigrationsBundle.message("liquibase.gutter.name")

  override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.firstChild != null) return null
    val include = includeAt(element) ?: return null
    if (include.path.contains("\${")) return null
    val file = element.containingFile ?: return null
    val changelog = file.virtualFile ?: return null
    val module = moduleOf(file) ?: return null
    if (!MigrationsGate.uses(module, "liquibase-core")) return null
    val target: VirtualFile = resolveInclude(module, changelog, include.path, include.relative, include.all) ?: return null
    val manager = PsiManager.getInstance(file.project)
    val psi = (if (target.isDirectory) manager.findDirectory(target) else manager.findFile(target)) ?: return null
    return NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
      .setTargets(psi)
      .setTooltipText(PhantomMigrationsBundle.message("liquibase.gutter.tooltip", include.path))
      .createLineMarkerInfo(element)
  }

  private class Include(val path: String, val relative: Boolean, val all: Boolean)

  private fun includeAt(element: PsiElement): Include? {
    // XML: the name token of <include file="..."/> or <includeAll path="..."/>
    val tag = element.parent as? XmlTag
    if (tag != null) {
      if (element.node.elementType != XmlTokenType.XML_NAME || element.prevSibling?.node?.elementType != XmlTokenType.XML_START_TAG_START) return null
      if (tag.parentTag?.localName != "databaseChangeLog") return null
      val all = when (tag.localName) { "include" -> false; "includeAll" -> true; else -> return null }
      val path = tag.getAttributeValue(if (all) "path" else "file") ?: return null
      return Include(path, tag.getAttributeValue("relativeToChangelogFile") == "true", all)
    }
    // YAML: the key of `- include: {file: ...}` in databaseChangeLog
    val kv = element.parent as? YAMLKeyValue ?: return null
    if (kv.key != element) return null
    val all = when (kv.keyText) { "include" -> false; "includeAll" -> true; else -> return null }
    val item = kv.parentMapping?.parent as? YAMLSequenceItem ?: return null
    if ((item.parent?.parent as? YAMLKeyValue)?.keyText != "databaseChangeLog") return null
    val body = kv.value as? YAMLMapping ?: return null
    val path = (body.getKeyValueByKey(if (all) "path" else "file")?.value as? YAMLScalar)?.textValue ?: return null
    return Include(path, (body.getKeyValueByKey("relativeToChangelogFile")?.value as? YAMLScalar)?.textValue == "true", all)
  }
}
