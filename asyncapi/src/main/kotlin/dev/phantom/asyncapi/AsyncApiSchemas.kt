// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.asyncapi

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** The major AsyncAPI version a YAML or JSON file declares near its top, read from text so no file is parsed. */
object AsyncApiVersion {
  private val EXTENSIONS = setOf("yaml", "yml", "json")
  private val VERSION = Regex("""(?m)^asyncapi\s*:\s*['"]?(\d+)\.|"asyncapi"\s*:\s*"(\d+)\.""")

  fun of(file: VirtualFile): Int? {
    if (file.isDirectory || file.extension?.lowercase() !in EXTENSIONS) return null
    // An open editor has the text being typed; the file on disk may still lack the asyncapi key.
    val head = FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence?.let { it.subSequence(0, minOf(it.length, HEAD)) }
      ?: runCatching { VfsUtilCore.loadText(file, HEAD) }.getOrNull() ?: return null
    val match = VERSION.find(head) ?: return null
    return (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
  }

  private const val HEAD = 4096
}

/** Bundled schemas for AsyncAPI 2 and 3 documents, which drive the YAML and JSON plugins' completion and validation. */
class AsyncApiSchemaProviderFactory : JsonSchemaProviderFactory {
  override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider(2), Provider(3))

  private class Provider(private val major: Int) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean = AsyncApiVersion.of(file) == major
    override fun getName(): String = AsyncApiBundle.message("schema.name", major)
    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(Provider::class.java, "/schemas/asyncapi-$major.json")
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
  }
}
