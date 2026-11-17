// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.docker

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.io.File
import java.nio.file.Path

/**
 * The `docker` command line. An app started from the macOS Dock gets a short PATH, so the usual install locations
 * are tried after PATH. Everything here is blocking: call it off the EDT.
 */
object DockerCli {
  private val FALLBACKS = listOf(
    "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker",
    System.getProperty("user.home") + "/.docker/bin/docker", System.getProperty("user.home") + "/.orbstack/bin/docker",
    System.getProperty("user.home") + "/.rd/bin/docker", "/usr/bin/docker",
  )

  @Volatile private var cached: String? = null

  fun executable(): String? {
    cached?.takeIf { File(it).canExecute() }?.let { return it }
    val found = PathEnvironmentVariableUtil.findInPath("docker")?.path ?: FALLBACKS.firstOrNull { File(it).canExecute() }
    cached = found
    return found
  }

  fun command(vararg args: String, workDir: Path? = null): GeneralCommandLine {
    val exe = executable() ?: throw DockerNotFoundException()
    return GeneralCommandLine(exe, *args)
      .withWorkingDirectory(workDir)
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
      // credential helpers (docker-credential-desktop) live next to docker; make sure they are found
      .withEnvironment("PATH", (listOf(File(exe).parent) + System.getenv("PATH").orEmpty().split(File.pathSeparator)).distinct().joinToString(File.pathSeparator))
  }

  fun run(vararg args: String, timeoutMs: Int = 15_000): ProcessOutput = CapturingProcessHandler(command(*args)).runProcess(timeoutMs)

  /** One container from `docker ps -a`. */
  data class Container(val id: String, val name: String, val image: String, val status: String, val state: String,
                       val ports: String, val composeProject: String, val composeService: String) {
    val running: Boolean get() = state == "running" || state == "restarting"
  }

  const val PS_FORMAT: String = "{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.State}}\t{{.Ports}}\t" +
                                "{{.Label \"com.docker.compose.project\"}}\t{{.Label \"com.docker.compose.service\"}}"

  fun parseContainers(output: String): List<Container> = output.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
    val f = line.split('\t')
    if (f.size < 6) null
    else Container(f[0], f[1], f[2], f[3], f[4].lowercase(), f[5], f.getOrElse(6) { "" }, f.getOrElse(7) { "" })
  }.toList()

  fun containers(): List<Container> {
    val out = run("ps", "-a", "--no-trunc", "--format", PS_FORMAT)
    if (out.exitCode != 0 || out.isTimeout) throw DockerException(out.stderr.trim().ifEmpty { "docker ps failed (exit ${out.exitCode})" })
    return parseContainers(out.stdout)
  }
}

open class DockerException(message: String) : Exception(message)

class DockerNotFoundException : DockerException("The docker command was not found. Install Docker Desktop, OrbStack or Colima, or put docker on PATH.")
