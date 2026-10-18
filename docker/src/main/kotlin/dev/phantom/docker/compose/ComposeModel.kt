// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker.compose

import com.intellij.DynamicBundle
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.phantom.docker.ComposeFiles
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

private const val BUNDLE = "messages.PhantomDockerBundle"

object PhantomDockerBundle : DynamicBundle(BUNDLE) {
  @Nls
  fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/**
 * The keys of the Compose Specification at the levels the checks look at. Only these levels are reported on;
 * `x-` extensions and the `<<` merge key are allowed everywhere.
 */
object ComposeSchema {
  val TOP: Set<String> = setOf("version", "name", "services", "networks", "volumes", "configs", "secrets", "include", "models")

  val SERVICE: Set<String> = setOf(
    "annotations", "attach", "build", "blkio_config", "cap_add", "cap_drop", "cgroup", "cgroup_parent", "command", "configs",
    "container_name", "cpu_count", "cpu_percent", "cpu_shares", "cpu_period", "cpu_quota", "cpu_rt_runtime", "cpu_rt_period",
    "cpus", "cpuset", "credential_spec", "depends_on", "deploy", "develop", "device_cgroup_rules", "devices", "dns", "dns_opt",
    "dns_search", "domainname", "driver_opts", "entrypoint", "env_file", "environment", "expose", "extends", "external_links",
    "extra_hosts", "gpus", "group_add", "healthcheck", "hostname", "image", "init", "ipc", "isolation", "labels", "label_file",
    "links", "logging", "mac_address", "mem_limit", "mem_reservation", "mem_swappiness", "memswap_limit", "models",
    "network_mode", "networks", "oom_kill_disable", "oom_score_adj", "pid", "pids_limit", "platform", "ports", "post_start",
    "pre_stop", "privileged", "profiles", "provider", "pull_policy", "pull_refresh_after", "read_only", "restart", "runtime",
    "scale", "secrets", "security_opt", "shm_size", "stdin_open", "stop_grace_period", "stop_signal", "storage_opt", "sysctls",
    "tmpfs", "tty", "ulimits", "use_api_socket", "user", "userns_mode", "uts", "volumes", "volumes_from", "working_dir",
  )

  val HEALTHCHECK: Set<String> = setOf("test", "interval", "timeout", "retries", "start_period", "start_interval", "disable")

  val BUILD: Set<String> = setOf(
    "context", "dockerfile", "dockerfile_inline", "args", "ssh", "cache_from", "cache_to", "additional_contexts", "entitlements",
    "extra_hosts", "isolation", "labels", "network", "no_cache", "platforms", "privileged", "pull", "secrets", "shm_size", "tags",
    "target", "ulimits",
  )

  val DEPENDS_ON_ENTRY: Set<String> = setOf("condition", "restart", "required")

  val CONDITIONS: Set<String> = setOf("service_started", "service_healthy", "service_completed_successfully")

  private val RESTART = Regex("""no|always|unless-stopped|on-failure(:\d+)?""")

  fun validRestart(value: String): Boolean = RESTART.matches(value)

  val PULL_POLICIES: List<String> = listOf("always", "never", "missing", "if_not_present", "build", "daily", "weekly")
  private val PULL_EVERY = Regex("""every_\d+[smhdw]?(\d+[smhdw])*""")

  fun validPullPolicy(value: String): Boolean = value in PULL_POLICIES || PULL_EVERY.matches(value)

  /** `network_mode`: none, host, bridge, or another service's or container's network. */
  fun validNetworkMode(value: String): Boolean =
    value in setOf("none", "host", "bridge", "default") || value.startsWith("service:") || value.startsWith("container:")

  fun allowed(key: String, known: Set<String>): Boolean = key in known || key.startsWith("x-") || key == "<<"
}

/** One compose file read as data: its services, networks and volumes. */
class ComposeModel(val services: Map<String, YAMLKeyValue>, val networks: Set<String>, val volumes: Set<String>, val hasInclude: Boolean) {
  companion object {
    fun of(file: PsiFile): ComposeModel? {
      if (file !is YAMLFile || !ComposeFiles.isComposeFile(file.name)) return null
      return CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(read(file), file)
      }
    }

    private fun read(file: YAMLFile): ComposeModel {
      val root = file.documents.firstOrNull()?.topLevelValue as? YAMLMapping
      fun names(key: String) = (root?.getKeyValueByKey(key)?.value as? YAMLMapping)?.keyValues?.associateBy { it.keyText }.orEmpty()
      return ComposeModel(names("services"), names("networks").keys, names("volumes").keys, root?.getKeyValueByKey("include") != null)
    }

    /**
     * Names this file and the other compose files in its directory define: `compose.override.yaml` refers to services
     * of `compose.yaml`, and `docker compose -f a -f b` merges files kept side by side.
     */
    fun withSiblings(file: PsiFile): Scope? {
      val own = of(file) ?: return null
      return CachedValuesManager.getCachedValue(file) {
        val siblings = file.originalFile.virtualFile?.parent?.children.orEmpty()
          .filter { it != file.originalFile.virtualFile && ComposeFiles.isComposeFile(it.name) }
          .mapNotNull { PsiManager.getInstance(file.project).findFile(it) }
          .mapNotNull { of(it) }
        val all = siblings + own
        val scope = Scope(
          all.flatMap { it.services.keys }.toSet(), all.flatMap { it.networks }.toSet(), all.flatMap { it.volumes }.toSet(),
          all.any { it.hasInclude }, siblings.isNotEmpty(),
          siblings.flatMap { s -> s.services.filterValues { definesImage(it) }.keys }.toSet(),
        )
        CachedValueProvider.Result.create(scope, PsiModificationTracker.getInstance(file.project))
      }
    }

    fun definesImage(service: YAMLKeyValue): Boolean {
      val mapping = service.value as? YAMLMapping ?: return true // an alias or merge: cannot tell, assume it does
      return mapping.getKeyValueByKey("image") != null || mapping.getKeyValueByKey("build") != null ||
             mapping.getKeyValueByKey("extends") != null || mapping.getKeyValueByKey("<<") != null || mapping.getKeyValueByKey("provider") != null
    }
  }

  data class Scope(val services: Set<String>, val networks: Set<String>, val volumes: Set<String>, val hasInclude: Boolean,
                   val hasSiblings: Boolean, val siblingImages: Set<String>)
}

/** Small helpers over YAML PSI. */
internal object Yaml {
  fun scalars(value: YAMLValue?): List<YAMLScalar> = when (value) {
    is YAMLSequence -> value.items.mapNotNull { it.value as? YAMLScalar }
    is YAMLScalar -> listOf(value)
    else -> emptyList()
  }

  fun keys(value: YAMLValue?): List<YAMLKeyValue> = (value as? YAMLMapping)?.keyValues?.toList().orEmpty()
}
