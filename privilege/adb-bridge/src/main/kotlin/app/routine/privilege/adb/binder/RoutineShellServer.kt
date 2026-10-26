package app.routine.privilege.adb.binder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import app.routine.privilege.api.ShellCommandResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-Kotlin Binder IPC interface between Routine's app process and its internal `app_process`
 * shell server (UID 2000). Uses standard Android `Parcel` interface tokens without Java codegen.
 */
interface RoutineShellService {
    fun getServerUid(): Int
    fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult
}

/**
 * Server-side [Binder] stub running inside Routine's internal `app_process` daemon as `shell` (UID 2000).
 */
class RoutineShellBinderStub :
    Binder(),
    RoutineShellService {
    override fun getServerUid(): Int = Process.myUid()

    override fun executeCommand(command: String, timeoutMs: Long): ShellCommandResult {
        if (command.isBlank()) {
            return ShellCommandResult.Failed("Empty command.")
        }
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command).start()
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
 * In-app [ContentProvider] receiving the live shell [IBinder] from [RoutineShellServerMain].
 * Accepts Binder handoff strictly from UID 0 (`root`), UID 2000 (`shell`), or Routine's own UID.
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
 * Entry point executed via `CLASSPATH=<apkPath> app_process /system/bin ...RoutineShellServerMain <pkg>`.
 * Publishes [RoutineShellBinderStub] to [RoutineShellBinderProvider] and loops cleanly on Binder IPC.
 */
object RoutineShellServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val targetPackage = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "app.routine"
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }
        val stub = RoutineShellBinderStub()
        publishBinderViaActivityManager(targetPackage, stub)
        Looper.loop()
    }

    @Suppress("PrivateApi")
    private fun publishBinderViaActivityManager(targetPackage: String, stub: IBinder) {
        val authority = "$targetPackage.shellbinder"
        val extras = Bundle().apply {
            putBinder(RoutineShellBinderProvider.KEY_SHELL_BINDER, stub)
        }
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMain = activityThreadClass.getMethod("systemMain").invoke(null)
            val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(systemMain)
                as android.content.Context
            systemContext.contentResolver.call(
                Uri.parse("content://$authority"),
                RoutineShellBinderProvider.METHOD_PUBLISH_SHELL_BINDER,
                null,
                extras,
            )
        }.onFailure { error ->
            System.err.println("RoutineShellServerMain failed to publish Binder: ${error.message}")
        }
    }
}
