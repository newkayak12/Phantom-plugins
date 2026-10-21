// Copyright 2026 Phantom IDEA contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import dev.phantom.db.core.DbDataSource
import dev.phantom.db.core.DbSshAuth
import dev.phantom.db.core.PhantomDbBundle
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * SSH tunnels with the IDE's bundled sshj: local port forwarding from `127.0.0.1:<ephemeral>` to the database through the
 * SSH server. Unknown host keys are shown to the user once and then added to `~/.ssh/known_hosts`; a changed key fails.
 */
class DbSshTunnelOpener(
  private val project: Project?,
  /** Asks whether to trust an unknown host key; called off the EDT. */
  private val confirmHostKey: (host: String, keyType: String, fingerprint: String) -> Boolean = { h, t, f -> askHostKey(project, h, t, f) },
) : DbTunnelOpener {
  override fun open(dataSource: DbDataSource, secret: String?, target: DbHostPort): DbTunnel {
    val host = dataSource.sshHost.trim()
    if (host.isEmpty()) throw DbException(PhantomDbBundle.message("db.ssh.host.required"))
    val user = dataSource.sshUser.trim().ifEmpty { System.getProperty("user.name") }
    val ssh = SSHClient(DefaultConfig())
    var server: ServerSocket? = null
    val knownHosts = KnownHosts(knownHostsFile(), confirmHostKey)
    try {
      ssh.connectTimeout = TIMEOUT_MS
      ssh.timeout = 0
      ssh.addHostKeyVerifier(knownHosts)
      ssh.connect(host, dataSource.sshPort.takeIf { it > 0 } ?: 22)
      when (dataSource.sshAuth) {
        DbSshAuth.PASSWORD -> ssh.authPassword(user, secret.orEmpty().toCharArray())
        DbSshAuth.KEY -> {
          val file = dataSource.sshKeyFile.trim().ifEmpty { throw DbException(PhantomDbBundle.message("db.ssh.key.required")) }
          val keys = if (secret.isNullOrEmpty()) ssh.loadKeys(expandHome(file)) else ssh.loadKeys(expandHome(file), secret.toCharArray())
          ssh.authPublickey(user, keys)
        }
        DbSshAuth.AGENT -> {
          val agent = DbSshAgent.connect() ?: throw DbException(PhantomDbBundle.message("db.ssh.agent.missing"))
          agent.use {
            val identities = it.identities()
            if (identities.isEmpty()) throw DbException(PhantomDbBundle.message("db.ssh.agent.empty"))
            ssh.auth(user, identities.map { blob -> AgentAuth(it, blob) })
          }
        }
      }
      ssh.connection.keepAlive.keepAliveInterval = 30
      server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
      }
      val forwarder = ssh.newLocalPortForwarder(Parameters("127.0.0.1", server.localPort, target.host, target.port), server)
      return SshTunnel(ssh, server, forwarder).also { it.start() }
    }
    catch (e: Exception) {
      runCatching { server?.close() }
      runCatching { ssh.disconnect() }
      if (e is DbException) throw e
      knownHosts.problem?.let { throw DbException(it, e) }
      throw DbException(PhantomDbBundle.message("db.ssh.failed", "$user@$host", e.message ?: e.javaClass.simpleName), e)
    }
  }

  private class SshTunnel(private val ssh: SSHClient, private val server: ServerSocket, private val forwarder: LocalPortForwarder) : DbTunnel {
    override val localPort: Int = server.localPort
    @Volatile private var closed = false

    fun start() {
      val thread = Thread({
        try {
          forwarder.listen()
        }
        catch (e: IOException) {
          if (!closed) logger<DbSshTunnelOpener>().info("SSH tunnel stopped: ${e.message}")
        }
      }, "Phantom DB SSH tunnel :$localPort")
      thread.isDaemon = true
      thread.start()
    }

    override fun isAlive(): Boolean = !closed && ssh.isConnected && ssh.isAuthenticated && !server.isClosed

    override fun close() {
      closed = true
      runCatching { forwarder.close() }
      runCatching { server.close() }
      runCatching { ssh.disconnect() }
    }
  }

  /** OpenSSH known hosts, adding a new host after the user saw its fingerprint (accept-new); a changed key is refused. */
  private class KnownHosts(file: File, private val confirm: (String, String, String) -> Boolean) : OpenSSHKnownHosts(file) {
    /** Why the host key was refused, for the error the user sees. */
    @Volatile var problem: String? = null

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
      val type = KeyType.fromKey(key)
      if (!confirm(hostname, type.toString(), fingerprint(key))) {
        problem = PhantomDbBundle.message("db.ssh.host.key.rejected", hostname)
        return false
      }
      try {
        val entry = HostEntry(null, hostname, type, key)
        entries().add(entry)
        file.parentFile?.mkdirs()
        write(entry)
      }
      catch (e: IOException) {
        logger<DbSshTunnelOpener>().warn("Could not add $hostname to ${file}", e)
      }
      return true
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
      problem = PhantomDbBundle.message("db.ssh.host.key.changed", hostname, fingerprint(key), file.path)
      return false
    }
  }

  /** Signs the user-auth request with a key held by the agent: the key never leaves it. */
  private class AgentAuth(private val agent: DbSshAgent, private val blob: ByteArray) : AbstractAuthMethod("publickey") {
    private val keyType: String = Buffer.PlainBuffer(blob).readString()
    /** RSA keys sign with SHA-256: servers refuse the old SHA-1 `ssh-rsa` signatures. */
    private val algorithm: String = if (keyType == "ssh-rsa") "rsa-sha2-256" else keyType

    override fun buildReq(): SSHPacket = request(false)

    private fun request(signed: Boolean): SSHPacket = super.buildReq().putBoolean(signed).putString(algorithm).putString(blob)

    override fun handle(cmd: Message, buf: SSHPacket) {
      if (cmd != Message.USERAUTH_60) return super.handle(cmd, buf)
      val req = request(true)
      val data = Buffer.PlainBuffer().putString(params.transport.sessionID).putBuffer(req).compactData
      val signature = agent.sign(blob, data, if (keyType == "ssh-rsa") DbSshAgent.RSA_SHA2_256 else 0)
      params.transport.write(req.putString(signature))
    }
  }

  companion object {
    private const val TIMEOUT_MS = 15_000

    fun knownHostsFile(): File = File(System.getProperty("user.home"), ".ssh/known_hosts")

    /** `SHA256:…` as OpenSSH prints it. */
    fun fingerprint(key: PublicKey): String {
      val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    internal fun expandHome(path: String): String = if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path

    private fun askHostKey(project: Project?, host: String, keyType: String, fingerprint: String): Boolean {
      var ok = false
      ApplicationManager.getApplication().invokeAndWait({
        ok = Messages.showYesNoDialog(project, PhantomDbBundle.message("db.ssh.host.key.unknown", host, keyType, fingerprint),
                                      PhantomDbBundle.message("db.ssh.host.key.title"), Messages.getQuestionIcon()) == Messages.YES
      }, ModalityState.any())
      return ok
    }
  }
}

/** The few messages of the OpenSSH agent protocol a tunnel needs: list the keys, sign with one. */
internal class DbSshAgent private constructor(private val io: Io) : AutoCloseable {
  private interface Io : AutoCloseable {
    fun write(bytes: ByteArray)
    fun readFully(n: Int): ByteArray
  }

  fun identities(): List<ByteArray> {
    val reply = call(byteArrayOf(REQUEST_IDENTITIES))
    if (reply.readByte() != IDENTITIES_ANSWER) throw IOException("SSH agent refused to list keys")
    val count = reply.readUInt32AsInt()
    return (0 until count).map { reply.readBytes().also { reply.readString() } }
  }

  fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray {
    val request = Buffer.PlainBuffer().putByte(SIGN_REQUEST).putString(blob).putString(data).putUInt32(flags.toLong()).compactData
    val reply = call(request)
    if (reply.readByte() != SIGN_RESPONSE) throw IOException("SSH agent refused to sign")
    return reply.readBytes()
  }

  private fun call(message: ByteArray): Buffer.PlainBuffer {
    io.write(ByteBuffer.allocate(4 + message.size).putInt(message.size).put(message).array())
    val length = ByteBuffer.wrap(io.readFully(4)).int
    if (length <= 0 || length > 256 * 1024) throw IOException("Bad SSH agent reply")
    return Buffer.PlainBuffer(io.readFully(length))
  }

  override fun close() = io.close()

  companion object {
    private const val REQUEST_IDENTITIES: Byte = 11
    private const val IDENTITIES_ANSWER: Byte = 12
    private const val SIGN_REQUEST: Byte = 13
    private const val SIGN_RESPONSE: Byte = 14
    const val RSA_SHA2_256: Int = 2

    /** `SSH_AUTH_SOCK` (a Unix socket), or the Windows OpenSSH agent's pipe. `null` when no agent runs. */
    fun connect(): DbSshAgent? {
      if (SystemInfo.isWindows) {
        val pipe = runCatching { RandomAccessFile("\\\\.\\pipe\\openssh-ssh-agent", "rw") }.getOrNull() ?: return null
        return DbSshAgent(object : Io {
          override fun write(bytes: ByteArray) = pipe.write(bytes)
          override fun readFully(n: Int): ByteArray = ByteArray(n).also { pipe.readFully(it) }
          override fun close() = pipe.close()
        })
      }
      val socket = System.getenv("SSH_AUTH_SOCK")?.takeIf { it.isNotEmpty() } ?: return null
      val channel = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(Path.of(socket))) }
      }.getOrNull() ?: return null
      return DbSshAgent(object : Io {
        override fun write(bytes: ByteArray) {
          val b = ByteBuffer.wrap(bytes)
          while (b.hasRemaining()) channel.write(b)
        }

        override fun readFully(n: Int): ByteArray {
          val b = ByteBuffer.allocate(n)
          while (b.hasRemaining()) if (channel.read(b) < 0) throw IOException("SSH agent closed the connection")
          return b.array()
        }

        override fun close() = channel.close()
      })
    }
  }
}
