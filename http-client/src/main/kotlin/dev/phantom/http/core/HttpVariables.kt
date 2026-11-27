// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.http.core

import com.intellij.ide.util.PropertiesComponent
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * One `http-client.env.json` or `http-client.private.env.json`. [auths] holds each environment's `Security.Auths`
 * entries (auth id to its settings), which requests use as `{{$auth.token("id")}}`.
 */
class HttpEnvFile(val file: VirtualFile, val environments: Map<String, Map<String, String>>,
                  val auths: Map<String, Map<String, Map<String, String>>> = emptyMap()) {
  val shared: Map<String, String> get() = environments[SHARED].orEmpty()
  val names: List<String> get() = environments.keys.filter { it != SHARED }

  companion object {
    const val SHARED: String = "\$shared"
  }
}

/** The env files that apply to one `.http` file: the nearest directory (up to the content root) that has any. */
class HttpEnvironments(val public: HttpEnvFile?, val private: HttpEnvFile?) {
  val names: List<String> get() = (public?.names.orEmpty() + private?.names.orEmpty()).distinct()
  val isEmpty: Boolean get() = public == null && private == null

  /** Private beats public, the selected environment beats `$shared`. */
  fun value(env: String?, name: String): String? {
    if (env != null) {
      private?.environments?.get(env)?.get(name)?.let { return it }
      public?.environments?.get(env)?.get(name)?.let { return it }
    }
    return private?.shared?.get(name) ?: public?.shared?.get(name)
  }

  /** Where [name] is defined: the env file for navigation. With no environment chosen, any environment counts. */
  fun definingFile(env: String?, name: String): VirtualFile? {
    val envs = if (env != null) listOf(env, HttpEnvFile.SHARED) else names + HttpEnvFile.SHARED
    for (file in listOfNotNull(private, public)) {
      if (envs.any { file.environments[it]?.containsKey(name) == true }) return file.file
    }
    return null
  }

  /** Auth ids of `Security.Auths` in [env] and `$shared`; with no environment chosen, of any environment. */
  fun authIds(env: String?): Set<String> {
    val envs = if (env != null) listOf(env, HttpEnvFile.SHARED) else names + HttpEnvFile.SHARED
    return listOfNotNull(private, public).flatMap { f -> envs.flatMap { f.auths[it]?.keys.orEmpty() } }.toSet()
  }

  fun authDefiningFile(env: String?, id: String): VirtualFile? {
    val envs = if (env != null) listOf(env, HttpEnvFile.SHARED) else names + HttpEnvFile.SHARED
    return listOfNotNull(private, public).firstOrNull { f -> envs.any { f.auths[it]?.containsKey(id) == true } }?.file
  }

  fun keys(env: String?): Set<String> {
    val envs = if (env != null) listOf(env, HttpEnvFile.SHARED) else names + HttpEnvFile.SHARED
    return listOfNotNull(private, public).flatMap { f -> envs.flatMap { f.environments[it]?.keys.orEmpty() } }.toSet()
  }
}

/**
 * Finds and parses env files on demand. Lookups walk up a few VFS directories; parsed content is kept per file and
 * re-read when the file's modification stamp changes. No listeners, no index.
 */
@Service(Service.Level.PROJECT)
class HttpEnvironmentService(private val project: Project) {
  private class Parsed(val stamp: Long, val env: HttpEnvFile?)

  private val parsed = ConcurrentHashMap<String, Parsed>()

  /** Counts environment selections, so values cached per `.http` file follow a switch. */
  val selectionTracker: SimpleModificationTracker = SimpleModificationTracker()

  fun environmentsFor(httpFile: VirtualFile): HttpEnvironments {
    val files = envFiles(httpFile)
    return HttpEnvironments(files.firstOrNull { it.name == PUBLIC_ENV }?.let(::parse), files.firstOrNull { it.name == PRIVATE_ENV }?.let(::parse))
  }

  /** The env files of the nearest directory (up to the content root) that has any. */
  fun envFiles(httpFile: VirtualFile): List<VirtualFile> {
    val stop = ProjectFileIndex.getInstance(project).getContentRootForFile(httpFile) ?: project.guessProjectDir()
    var dir = httpFile.parent
    while (dir != null) {
      val found = listOfNotNull(dir.findChild(PUBLIC_ENV), dir.findChild(PRIVATE_ENV))
      if (found.isNotEmpty()) return found
      if (dir == stop) break
      dir = dir.parent
    }
    return emptyList()
  }

  private fun parse(file: VirtualFile): HttpEnvFile? {
    // An env file open in an editor is read from its document, so variables added there count before it is saved.
    val document = FileDocumentManager.getInstance().getCachedDocument(file)
    val stamp = document?.modificationStamp ?: file.modificationStamp
    parsed[file.url]?.takeIf { it.stamp == stamp }?.let { return it.env }
    val env = try {
      val root = Json.parseToJsonElement(document?.text ?: VfsUtilCore.loadText(file)) as? JsonObject
      root?.let {
        val environments = it.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to flatten(it) } }.toMap()
        val auths = it.mapNotNull { (name, value) -> (value as? JsonObject)?.let { env -> authsOf(env)?.let { name to it } } }.toMap()
        HttpEnvFile(file, environments, auths)
      }
    }
    catch (_: Exception) {
      null // a half-edited env file: no variables from it until it parses again
    }
    parsed[file.url] = Parsed(stamp, env)
    return env
  }

  private fun authsOf(env: JsonObject): Map<String, Map<String, String>>? {
    val auths = ((env["Security"] as? JsonObject)?.get("Auths") as? JsonObject) ?: return null
    return auths.mapNotNull { (id, config) -> (config as? JsonObject)?.let { id to flatten(it) } }.toMap()
  }

  private fun flatten(obj: JsonObject): Map<String, String> =
    obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it.content } }.toMap()

  fun selectedEnvironment(httpFile: VirtualFile): String? =
    PropertiesComponent.getInstance(project).getValue(selectionKey(httpFile))
      ?.takeIf { it in environmentsFor(httpFile).names }

  fun selectEnvironment(httpFile: VirtualFile, env: String?) {
    PropertiesComponent.getInstance(project).setValue(selectionKey(httpFile), env)
    selectionTracker.incModificationCount()
    // Unresolved-variable warnings depend on the environment, and the daemon does not notice a property change.
    PsiManager.getInstance(project).findFile(httpFile)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
  }

  private fun selectionKey(file: VirtualFile) = "phantom.http.environment:" + file.path

  companion object {
    const val PUBLIC_ENV: String = "http-client.env.json"
    const val PRIVATE_ENV: String = "http-client.private.env.json"

    fun getInstance(project: Project): HttpEnvironmentService = project.service()
  }
}

/** Variable lookup shared by references, highlighting and the request builder. */
object HttpVariables {
  private const val MAX_DEPTH = 10

  val DYNAMIC: Map<String, String> = linkedMapOf(
    "\$uuid" to "random UUID",
    "\$random.uuid" to "random UUID",
    "\$timestamp" to "Unix time, seconds",
    "\$isoTimestamp" to "ISO-8601 UTC time",
    "\$randomInt" to "random 0..1000",
    "\$random.integer" to "random 0..1000",
  )

  fun dynamicValue(name: String): String? = when (name) {
    "\$uuid", "\$random.uuid" -> UUID.randomUUID().toString()
    "\$timestamp" -> (System.currentTimeMillis() / 1000).toString()
    "\$isoTimestamp" -> DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
    "\$randomInt", "\$random.integer" -> ThreadLocalRandom.current().nextInt(0, 1001).toString()
    else -> if (name.startsWith("\$env.")) System.getenv(name.removePrefix("\$env.")) else null
  }

  fun isDynamic(name: String): Boolean = name in DYNAMIC || name.startsWith("\$env.")

  /** `$auth.token("id")` / `$auth.idToken("id")`: group 1 is the function, group 2 the auth id. */
  val AUTH: Regex = Regex("""^\${'$'}auth\.(token|idToken)\(\s*["']([^"']*)["']\s*\)$""")

  /** The auth id a variable reference names, or null when it is not an auth reference. */
  fun authId(name: String): String? = AUTH.matchEntire(name.trim())?.groupValues?.get(2)

  /** Auth ids the env files define for [file]'s selected environment (any environment when none is chosen). */
  fun authIds(file: PsiFile): Set<String> {
    val (envs, selected) = environments(file)
    return envs.authIds(selected)
  }

  fun fileVariables(file: PsiFile): Map<String, HttpFileVariable> = CachedValuesManager.getCachedValue(file) {
    val map = LinkedHashMap<String, HttpFileVariable>()
    for (v in PsiTreeUtil.findChildrenOfType(file, HttpFileVariable::class.java)) map.putIfAbsent(v.name, v)
    CachedValueProvider.Result.create(map as Map<String, HttpFileVariable>, file)
  }

  /** File variables of the files this one imports (`import ./other.http`); this file's own come first. */
  fun importedFileVariables(file: PsiFile): Map<String, HttpFileVariable> {
    val httpFile = file as? HttpRequestFile ?: return emptyMap()
    val map = LinkedHashMap<String, HttpFileVariable>()
    for (imported in HttpImports.importedFiles(httpFile)) for ((name, v) in fileVariables(imported)) map.putIfAbsent(name, v)
    return map
  }

  /** Names a response handler sets with `client.global.set("name", ...)` somewhere in the file. */
  fun handlerGlobals(file: PsiFile): Set<String> = CachedValuesManager.getCachedValue(file) {
    val names = GLOBAL_SET.findAll(file.text).map { it.groupValues[1] }.toSet()
    CachedValueProvider.Result.create(names, file)
  }

  private val GLOBAL_SET = Regex("""client\.global\.set\(\s*["']([^"']+)["']""")

  private fun environments(file: PsiFile): Pair<HttpEnvironments, String?> {
    val vFile = file.originalFile.virtualFile ?: return HttpEnvironments(null, null) to null
    val service = HttpEnvironmentService.getInstance(file.project)
    return service.environmentsFor(vFile) to service.selectedEnvironment(vFile)
  }

  /** Whether [name] can get a value at run time; with no environment chosen, any environment counts. */
  fun isKnown(file: PsiFile, name: String): Boolean {
    // auth references are judged by their own inspection, which knows the auth ids
    if (authId(name) != null) return true
    return isDynamic(name) || name in knownNames(file)
  }

  /**
   * Names with a value for [file]: its file variables, handler globals, imported file variables and the selected
   * environment's keys. Kept per file, so highlighting does not walk env directories and imports for every reference.
   */
  private fun knownNames(file: PsiFile): Set<String> = CachedValuesManager.getCachedValue(file) {
    val names = HashSet<String>()
    names += fileVariables(file).keys
    names += handlerGlobals(file)
    names += importedFileVariables(file).keys
    val (envs, selected) = environments(file)
    names += envs.keys(selected)
    val dependencies = ArrayList<Any>()
    dependencies += file
    (file as? HttpRequestFile)?.let { dependencies.addAll(HttpImports.importedFiles(it)) }
    dependencies += VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS
    file.originalFile.virtualFile?.let { vFile ->
      val service = HttpEnvironmentService.getInstance(file.project)
      dependencies += service.selectionTracker
      for (env in service.envFiles(vFile)) dependencies += FileDocumentManager.getInstance().getDocument(env) ?: env
    }
    CachedValueProvider.Result.create(names as Set<String>, dependencies)
  }

  fun findDeclaration(ref: HttpVariableRef): PsiElement? {
    val file = ref.containingFile
    val name = ref.variableName
    val (envs, selected) = environments(file)
    authId(name)?.let { id ->
      val vFile = envs.authDefiningFile(selected, id) ?: return null
      val envPsi = PsiManager.getInstance(file.project).findFile(vFile) ?: return null
      val auths = envPsi.text.indexOf("\"Auths\"")
      val offset = if (auths >= 0) envPsi.text.indexOf("\"" + StringUtil.escapeStringCharacters(id) + "\"", auths) else -1
      return if (offset >= 0) envPsi.findElementAt(offset + 1) ?: envPsi else envPsi
    }
    envs.definingFile(selected, name)?.let { vFile ->
      val envPsi = PsiManager.getInstance(file.project).findFile(vFile) ?: return null
      val offset = envPsi.text.indexOf("\"" + StringUtil.escapeStringCharacters(name) + "\"")
      return if (offset >= 0) envPsi.findElementAt(offset + 1) ?: envPsi else envPsi
    }
    return fileVariables(file)[name] ?: importedFileVariables(file)[name]
  }

  /** Completion candidates with where they come from. */
  fun candidates(ref: HttpVariableRef): List<Pair<String, String>> {
    val file = ref.containingFile
    val (envs, selected) = environments(file)
    val result = LinkedHashMap<String, String>()
    for (name in envs.keys(selected)) result.putIfAbsent(name, selected ?: "environment")
    for (name in fileVariables(file).keys) result.putIfAbsent(name, "file variable")
    for ((name, v) in importedFileVariables(file)) result.putIfAbsent(name, v.containingFile.name)
    for (name in handlerGlobals(file)) result.putIfAbsent(name, "set by handler")
    for ((name, description) in DYNAMIC) result.putIfAbsent(name, description)
    return result.map { it.key to it.value }
  }

  /**
   * Resolves variables for a run: handler globals, then the environment, then file variables, then dynamic values.
   * Values may reference other variables.
   */
  class Resolver(
    private val environments: HttpEnvironments,
    private val environment: String?,
    private val fileValues: Map<String, String>,
    private val globals: Map<String, String>,
    /** Values a `run ... (@name=value)` line gives; they win over everything else. */
    private val overrides: Map<String, String> = emptyMap(),
  ) {
    val unresolved: MutableSet<String> = LinkedHashSet()
    private val dynamicCache = HashMap<String, String>()

    fun substitute(text: String, depth: Int = 0): String {
      if (!text.contains("{{")) return text
      val sb = StringBuilder()
      var i = 0
      while (i < text.length) {
        val open = text.indexOf("{{", i)
        val close = if (open >= 0) text.indexOf("}}", open + 2) else -1
        if (open < 0 || close < 0) {
          sb.append(text, i, text.length)
          break
        }
        sb.append(text, i, open)
        val name = text.substring(open + 2, close).trim()
        val value = value(name, depth)
        if (value == null) {
          unresolved.add(name)
          sb.append(text, open, close + 2)
        }
        else {
          sb.append(value)
        }
        i = close + 2
      }
      return sb.toString()
    }

    /** The value [name] has for this run, for `request.environment.get(name)` in handlers. */
    fun lookup(name: String): String? = value(name, 0)

    private fun value(name: String, depth: Int): String? {
      if (depth > MAX_DEPTH) return null
      overrides[name]?.let { return substitute(it, depth + 1) }
      globals[name]?.let { return it }
      environments.value(environment, name)?.let { return substitute(it, depth + 1) }
      fileValues[name]?.let { return substitute(it, depth + 1) }
      // A dynamic value is computed once per request, so `{{$uuid}}` twice gives the same id.
      return dynamicCache[name] ?: dynamicValue(name)?.also { dynamicCache[name] = it }
    }
  }

  fun resolver(file: PsiFile, globals: Map<String, String>, environment: String?, overrides: Map<String, String> = emptyMap()): Resolver {
    val (envs, _) = environments(file)
    val values = (importedFileVariables(file) + fileVariables(file)).mapValues { it.value.rawValue }
    return Resolver(envs, environment, values, globals, overrides)
  }
}
