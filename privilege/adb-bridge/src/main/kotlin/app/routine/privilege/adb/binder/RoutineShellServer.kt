package app.routine.privilege.adb.binder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import app.routine.privilege.api.ShellCommandResult
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin IPC interface between Routine's app process and its internal `app_process`
 * shell server (UID 2000).
 */
interface RoutineShellService {
    fun getServerUid(): Int
    fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult
}

/**
 * Shared command runner executing `sh -c <command>` with bounded timeout and stream draining.
 */
internal object RoutineShellCommandRunner {
    fun currentUid(): Int = runCatching { Process.myUid() }.getOrDefault(0)

    fun execute(command: String, timeoutMs: Long): ShellCommandResult {
        if (command.isBlank()) {
            return ShellCommandResult.Failed("Empty command.")
        }
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command).start()
            process.outputStream.close()
            val finished = process.waitFor(timeoutMs.coerceIn(250L, 30_000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellCommandResult.Failed("Command timed out after ${timeoutMs}ms.")
            }
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val code = process.exitValue()
            if (code == 0) {
                ShellCommandResult.Succeeded(stdout = stdout, stderr = stderr, exitCode = 0)
            } else {
                ShellCommandResult.Failed(
                    reason = stderr.ifBlank { stdout.ifBlank { "Exited with code $code" } },
                    exitCode = code,
                )
            }
        }.getOrElse { error ->
            ShellCommandResult.Failed("Shell execution error: ${error.message}")
        }
    }
}

/**
 * Zero-hidden-API `127.0.0.1` loopback shell daemon protected by per-request 128-bit challenge nonces
 * and 256-bit `HMAC-SHA256` authentication. Runs inside `app_process` as `UID 2000` (`shell`) across
 * Android 11–16 and OEM skins without `ActivityThread` or `AttributionSource` reflection.
 */
class LoopbackRoutineShellDaemon(
    private val secretKey: ByteArray,
    private val uidProvider: () -> Int = RoutineShellCommandRunner::currentUid,
    private val commandExecutor: (String, Long) -> ShellCommandResult = RoutineShellCommandRunner::execute,
) : Closeable {
    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val workerPool = Executors.newCachedThreadPool()
    private val random = SecureRandom()

    val localPort: Int
        get() = serverSocket.localPort

    fun notifyReadyCallback(callbackPort: Int) {
        if (callbackPort <= 0) return
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), callbackPort), 3_000)
            sock.soTimeout = 3_000
            val out = DataOutputStream(sock.outputStream)
            val uid = uidProvider()
            val mac = LoopbackShellCrypto.computeReadyMac(secretKey, localPort, uid)
            out.writeInt(localPort)
            out.writeInt(uid)
            out.write(mac)
            out.flush()
        }
    }

    fun serveBlocking() {
        while (running.get() && !serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
            workerPool.execute { handleClient(client) }
        }
    }

    fun startInBackground() {
        workerPool.execute { serveBlocking() }
    }

    private fun handleClient(socket: Socket) {
        runCatching {
            socket.use { client ->
                client.tcpNoDelay = true
                client.soTimeout = 35_000
                val input = DataInputStream(client.inputStream)
                val output = DataOutputStream(client.outputStream)

                val challenge = ByteArray(16).also { random.nextBytes(it) }
                output.write(challenge)
                output.flush()

                val op = input.readByte()
                val timeoutMs = input.readLong()
                val cmdLen = input.readInt()
                require(cmdLen in 0..MAX_CMD_BYTES) { "Invalid command byte length $cmdLen" }
                val cmdBytes = ByteArray(cmdLen)
                if (cmdLen > 0) input.readFully(cmdBytes)
                val providedMac = ByteArray(32)
                input.readFully(providedMac)

                val expectedMac = LoopbackShellCrypto.computeRequestMac(
                    secretKey = secretKey,
                    challenge = challenge,
                    op = op,
                    timeoutMs = timeoutMs,
                    cmdBytes = cmdBytes,
                )
                if (!MessageDigest.isEqual(expectedMac, providedMac)) {
                    return
                }

                when (op) {
                    OP_GET_UID -> {
                        output.writeByte(1)
                        output.writeInt(uidProvider())
                        output.flush()
                    }
                    OP_EXEC -> {
                        val res = commandExecutor(cmdBytes.decodeToString(), timeoutMs)
                        writeExecResponse(output, res)
                    }
                }
            }
        }.onFailure { err ->
            if (running.get()) {
                System.err.println("LoopbackRoutineShellDaemon client error: ${err.message}")
            }
        }
    }

    private fun writeExecResponse(output: DataOutputStream, res: ShellCommandResult) {
        when (res) {
            is ShellCommandResult.Succeeded -> {
                val outBytes = res.stdout.encodeToByteArray()
                val errBytes = res.stderr.encodeToByteArray()
                output.writeByte(1)
                output.writeInt(res.exitCode)
                output.writeInt(outBytes.size)
                output.write(outBytes)
                output.writeInt(errBytes.size)
                output.write(errBytes)
            }
            is ShellCommandResult.Failed -> {
                val reasonBytes = res.reason.encodeToByteArray()
                output.writeByte(0)
                output.writeInt(res.exitCode ?: -1)
                output.writeInt(reasonBytes.size)
                output.write(reasonBytes)
                output.writeInt(0)
            }
        }
        output.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
            .onFailure { err -> System.err.println("Ignored daemon socket close: ${err.message}") }
        workerPool.shutdownNow()
    }

    companion object {
        const val OP_GET_UID: Byte = 1
        const val OP_EXEC: Byte = 2
        private const val MAX_CMD_BYTES = 65_536
    }
}

/**
 * Authenticated `127.0.0.1` loopback client implementing [RoutineShellService].
 */
class LoopbackRoutineShellClient(
    val port: Int,
    private val secretKey: ByteArray,
) : RoutineShellService {
    fun isAlive(): Boolean = runCatching { getServerUid() >= 0 }.getOrDefault(false)

    override fun getServerUid(): Int {
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1_500)
            sock.soTimeout = 2_500
            val input = DataInputStream(sock.inputStream)
            val output = DataOutputStream(sock.outputStream)
            val challenge = ByteArray(16).also { input.readFully(it) }
            val empty = ByteArray(0)
            val mac = LoopbackShellCrypto.computeRequestMac(
                secretKey = secretKey,
                challenge = challenge,
                op = LoopbackRoutineShellDaemon.OP_GET_UID,
                timeoutMs = 0L,
                cmdBytes = empty,
            )
            output.writeByte(LoopbackRoutineShellDaemon.OP_GET_UID.toInt())
            output.writeLong(0L)
            output.writeInt(0)
            output.write(mac)
            output.flush()

            val status = input.readByte().toInt()
            check(status == 1) { "Unexpected GET_UID status $status" }
            return input.readInt()
        }
    }

    override fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult {
        if (command.isBlank()) return ShellCommandResult.Failed("Empty command.")
        return runCatching {
            Socket().use { sock ->
                val boundedTimeout = timeoutMs.coerceIn(500L, 30_000L)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000)
                sock.soTimeout = (boundedTimeout + 3_000L).toInt()
                val input = DataInputStream(sock.inputStream)
                val output = DataOutputStream(sock.outputStream)
                val challenge = ByteArray(16).also { input.readFully(it) }
                val cmdBytes = command.encodeToByteArray()
                val mac = LoopbackShellCrypto.computeRequestMac(
                    secretKey = secretKey,
                    challenge = challenge,
                    op = LoopbackRoutineShellDaemon.OP_EXEC,
                    timeoutMs = boundedTimeout,
                    cmdBytes = cmdBytes,
                )
                output.writeByte(LoopbackRoutineShellDaemon.OP_EXEC.toInt())
                output.writeLong(boundedTimeout)
                output.writeInt(cmdBytes.size)
                output.write(cmdBytes)
                output.write(mac)
                output.flush()

                val status = input.readByte().toInt()
                val exitCode = input.readInt()
                val primaryLen = input.readInt().coerceIn(0, 1_048_576)
                val primaryBytes = ByteArray(primaryLen).also { if (primaryLen > 0) input.readFully(it) }
                val secondaryLen = input.readInt().coerceIn(0, 1_048_576)
                val secondaryBytes = ByteArray(secondaryLen).also { if (secondaryLen > 0) input.readFully(it) }
                if (status == 1) {
                    ShellCommandResult.Succeeded(
                        stdout = primaryBytes.decodeToString(),
                        stderr = secondaryBytes.decodeToString(),
                        exitCode = exitCode,
                    )
                } else {
                    ShellCommandResult.Failed(
                        reason = primaryBytes.decodeToString(),
                        exitCode = exitCode.takeIf { it >= 0 },
                    )
                }
            }
        }.getOrElse { err ->
            ShellCommandResult.Failed("Loopback shell IPC failed: ${err.message}")
        }
    }
}

/**
 * HMAC-SHA256 helper and session store for [LoopbackRoutineShellDaemon] / [LoopbackRoutineShellClient].
 */
object LoopbackShellCrypto {
    val activeLoopbackClient: AtomicReference<LoopbackRoutineShellClient?> = AtomicReference(null)

    fun generateSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == 64) { "Expected 64-char hex secret." }
        return ByteArray(32) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    fun computeReadyMac(secretKey: ByteArray, port: Int, uid: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretKey, "HmacSHA256"))
        }
        return mac.doFinal("READY|$port|$uid".encodeToByteArray())
    }

    fun computeRequestMac(
        secretKey: ByteArray,
        challenge: ByteArray,
        op: Byte,
        timeoutMs: Long,
        cmdBytes: ByteArray,
    ): ByteArray {
        val header = ByteBuffer.allocate(1 + 8 + 4)
            .put(op)
            .putLong(timeoutMs)
            .putInt(cmdBytes.size)
            .array()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretKey, "HmacSHA256"))
            update(challenge)
            update(header)
            update(cmdBytes)
        }
        return mac.doFinal()
    }

    fun saveEndpoint(context: Context, port: Int, secretKey: ByteArray) {
        runCatching {
            val file = File(context.noBackupFilesDir, "adb_shell_daemon.properties")
            val props = Properties().apply {
                setProperty("port", port.toString())
                setProperty("secret", toHex(secretKey))
            }
            file.outputStream().use { props.store(it, "Routine Loopback Shell Session") }
        }.onFailure { err ->
            android.util.Log.w("LoopbackShellCrypto", "Unable to persist shell endpoint", err)
        }
    }

    fun resolveActiveOrRestored(context: Context): LoopbackRoutineShellClient? {
        activeLoopbackClient.get()?.let { existing ->
            if (existing.isAlive()) return existing
            activeLoopbackClient.compareAndSet(existing, null)
        }
        val file = File(context.noBackupFilesDir, "adb_shell_daemon.properties")
        if (!file.exists()) return null
        return runCatching {
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val port = props.getProperty("port")?.toIntOrNull() ?: return null
            val secret = fromHex(props.getProperty("secret").orEmpty())
            val candidate = LoopbackRoutineShellClient(port, secret)
            if (candidate.isAlive()) {
                activeLoopbackClient.set(candidate)
                candidate
            } else {
                null
            }
        }.onFailure { err ->
            android.util.Log.d("LoopbackShellCrypto", "Stale shell endpoint ignored", err)
        }.getOrNull()
    }
}

/**
 * Server-side [Binder] stub running inside Routine's internal `app_process` daemon as `shell` (UID 2000).
 */
class RoutineShellBinderStub :
    Binder(),
    RoutineShellService {
    override fun getServerUid(): Int = Process.myUid()

    override fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult =
        RoutineShellCommandRunner.execute(command, timeoutMs)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_GET_UID -> {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(getServerUid())
                true
            }
            TRANSACTION_EXEC_COMMAND -> {
                data.enforceInterface(DESCRIPTOR)
                val cmd = data.readString().orEmpty()
                val timeout = data.readLong()
                val res = executeCommand(cmd, timeout)
                reply?.writeNoException()
                when (res) {
                    is ShellCommandResult.Succeeded -> {
                        reply?.writeInt(1)
                        reply?.writeInt(res.exitCode)
                        reply?.writeString(res.stdout)
                        reply?.writeString(res.stderr)
                    }
                    is ShellCommandResult.Failed -> {
                        reply?.writeInt(0)
                        reply?.writeInt(res.exitCode ?: -1)
                        reply?.writeString(res.reason)
                    }
                }
                true
            }
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        const val DESCRIPTOR: String = "app.routine.privilege.adb.binder.IRoutineShellService"
        const val TRANSACTION_GET_UID: Int = FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_EXEC_COMMAND: Int = FIRST_CALL_TRANSACTION + 2
    }
}

/**
 * Client-side [IBinder] proxy invoking [RoutineShellBinderStub] across process boundaries.
 */
class RoutineShellBinderProxy(private val remote: IBinder) : RoutineShellService {
    fun isBinderAlive(): Boolean = remote.isBinderAlive && remote.pingBinder()

    override fun getServerUid(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RoutineShellBinderStub.DESCRIPTOR)
            remote.transact(RoutineShellBinderStub.TRANSACTION_GET_UID, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RoutineShellBinderStub.DESCRIPTOR)
            data.writeString(command)
            data.writeLong(timeoutMs)
            val ok = remote.transact(RoutineShellBinderStub.TRANSACTION_EXEC_COMMAND, data, reply, 0)
            if (!ok) return ShellCommandResult.Failed("Binder transaction returned false.")
            reply.readException()
            val status = reply.readInt()
            val exitCode = reply.readInt()
            if (status == 1) {
                val stdout = reply.readString().orEmpty()
                val stderr = reply.readString().orEmpty()
                ShellCommandResult.Succeeded(stdout = stdout, stderr = stderr, exitCode = exitCode)
            } else {
                val reason = reply.readString().orEmpty()
                ShellCommandResult.Failed(reason = reason, exitCode = exitCode.takeIf { it >= 0 })
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/**
 * In-app [ContentProvider] receiving the live shell [IBinder] when Binder handoff is used.
 */
class RoutineShellBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_PUBLISH_SHELL_BINDER) {
            val callingUid = Binder.getCallingUid()
            val myUid = Process.myUid()
            if (callingUid != SHELL_UID && callingUid != ROOT_UID && callingUid != myUid) {
                throw SecurityException("Rejected Binder registration from unauthorized UID=$callingUid")
            }
            val binder = extras?.getBinder(KEY_SHELL_BINDER) ?: return null
            BinderHolder.activeProxy.set(RoutineShellBinderProxy(binder))
            return Bundle().apply { putBoolean("accepted", true) }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    object BinderHolder {
        val activeProxy: AtomicReference<RoutineShellBinderProxy?> = AtomicReference(null)
    }

    companion object {
        const val METHOD_PUBLISH_SHELL_BINDER: String = "publishShellBinder"
        const val KEY_SHELL_BINDER: String = "shell_binder"
        private const val SHELL_UID: Int = 2000
        private const val ROOT_UID: Int = 0
    }
}

/**
 * Entry point executed via `CLASSPATH=<apkPath> app_process /system/bin ...RoutineShellServerMain`.
 * Supports `--loopback <secretHex> <callbackPort>` (primary, zero-hidden-API authenticated loopback RPC).
 */
object RoutineShellServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--loopback" && args.size >= 3) {
            val secret = LoopbackShellCrypto.fromHex(args[1])
            val callbackPort = args[2].toIntOrNull() ?: 0
            LoopbackRoutineShellDaemon(secret).use { daemon ->
                daemon.notifyReadyCallback(callbackPort)
                daemon.serveBlocking()
            }
            return
        }

        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        Looper.loop()
    }
}
