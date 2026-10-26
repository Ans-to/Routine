package app.routine.privilege.grant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.routine.privilege.api.OneTimeGrantTier
import app.routine.privilege.api.PrivilegeAvailability
import app.routine.privilege.api.PrivilegeOperationResult
import app.routine.privilege.api.PrivilegedEngine
import app.routine.privilege.api.SecureSettingNamespace
import app.routine.privilege.api.ShellCommandResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Option B implementation of [PrivilegedEngine]: uses a persistent one-time `WRITE_SECURE_SETTINGS`
 * permission grant across reboots without any background shell daemon.
 */
@Singleton
class OneTimeGrantPrivilegedEngine(
    private val context: Context,
    private val hasSecureGrantCheck: (Context) -> Boolean,
) : PrivilegedEngine {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        hasSecureGrantCheck = { ctx ->
            ContextCompat.checkSelfPermission(
                ctx,
                OneTimeGrantConstants.WRITE_SECURE_SETTINGS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        },
    )

    override suspend fun checkAvailability(): PrivilegeAvailability = if (hasSecureGrantCheck(context)) {
        PrivilegeAvailability.OneTimeGrantActive
    } else {
        PrivilegeAvailability.NotConfigured(
            reason = "WRITE_SECURE_SETTINGS has not been granted yet.",
            setupHint = OneTimeGrantSetupHelper.buildAdbGrantCommand(context.packageName),
        )
    }

    override suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellCommandResult =
        ShellCommandResult.Failed(
            reason = "One-Time ADB Grant supports Settings.Secure/Global writes only; " +
                "enable Wireless Debugging ADB Bridge for shell commands.",
        )

    override suspend fun grantPermission(packageName: String, permission: String): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(packageName) || !PrivilegedEngine.isSafeIdentifier(permission)) {
            return PrivilegeOperationResult.Failed("Invalid package or permission identifier.")
        }
        val alreadyGranted = packageName == context.packageName &&
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return if (alreadyGranted) {
            PrivilegeOperationResult.Succeeded("Permission $permission is already granted.")
        } else {
            PrivilegeOperationResult.Failed(
                "Run `adb shell pm grant $packageName $permission` or connect Wireless Debugging ADB Bridge.",
            )
        }
    }

    override suspend fun writeSecureSetting(
        namespace: SecureSettingNamespace,
        key: String,
        value: String,
    ): PrivilegeOperationResult {
        if (!PrivilegedEngine.isSafeIdentifier(key)) {
            return PrivilegeOperationResult.Failed("Invalid setting key '$key'.")
        }
        if (namespace != SecureSettingNamespace.SYSTEM && !hasSecureGrantCheck(context)) {
            return PrivilegeOperationResult.Failed(
                "WRITE_SECURE_SETTINGS is not granted. Run: " +
                    OneTimeGrantSetupHelper.buildAdbGrantCommand(context.packageName),
            )
        }
        return try {
            val wrote = when (namespace) {
                SecureSettingNamespace.SECURE -> Settings.Secure.putString(context.contentResolver, key, value)
                SecureSettingNamespace.GLOBAL -> Settings.Global.putString(context.contentResolver, key, value)
                SecureSettingNamespace.SYSTEM -> Settings.System.putString(context.contentResolver, key, value)
            }
            if (wrote) {
                PrivilegeOperationResult.Succeeded("Wrote ${namespace.name}:$key")
            } else {
                PrivilegeOperationResult.Failed("ContentResolver rejected write to ${namespace.name}:$key")
            }
        } catch (error: SecurityException) {
            android.util.Log.w(TAG, "SecurityException writing ${namespace.name}:$key", error)
            PrivilegeOperationResult.Failed("Missing OS permission to write ${namespace.name}:$key (${error.message})")
        }
    }

    companion object {
        private const val TAG: String = "OneTimeGrantEngine"
    }
}

/**
 * Helper generating the exact one-time `adb` command, clipboard copy action, and pure-Kotlin QR code matrix.
 */
object OneTimeGrantSetupHelper {
    fun buildAdbGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName ${OneTimeGrantConstants.WRITE_SECURE_SETTINGS_PERMISSION}"

    fun copyCommandToClipboard(context: Context): String {
        val command = buildAdbGrantCommand(context.packageName)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Routine ADB Grant Command", command))
        return command
    }

    /**
     * Encodes an ASCII payload up to 78 bytes into an ISO/IEC 18004 Version 4-L (33x33) QR code boolean matrix.
     */
    fun encodeQrMatrix(payload: String): Array<BooleanArray> = QrVersion4Encoder.encode(payload)
}

/**
 * Pure-Kotlin ISO/IEC 18004 Version 4-L (33x33) QR Code encoder (Byte mode, 80 data codewords, 20 EC codewords).
 */
internal object QrVersion4Encoder {
    private const val SIZE = 33
    private const val DATA_CODEWORDS = 80
    private const val EC_CODEWORDS = 20

    fun encode(text: String): Array<BooleanArray> {
        val utf8 = text.encodeToByteArray().let { if (it.size > 78) it.copyOf(78) else it }
        val dataCw = buildDataCodewords(utf8)
        val ecCw = computeReedSolomon(dataCw)
        val allCw = IntArray(DATA_CODEWORDS + EC_CODEWORDS).also { dst ->
            dataCw.copyInto(dst, 0)
            ecCw.copyInto(dst, DATA_CODEWORDS)
        }

        val modules = Array(SIZE) { BooleanArray(SIZE) }
        val reserved = Array(SIZE) { BooleanArray(SIZE) }

        placeFinderPattern(modules, reserved, 0, 0)
        placeFinderPattern(modules, reserved, SIZE - 7, 0)
        placeFinderPattern(modules, reserved, 0, SIZE - 7)
        placeAlignmentPattern(modules, reserved, 26, 26)
        placeTimingPatterns(modules, reserved)
        reserveFormatAreas(modules, reserved)
        placeCodewords(modules, reserved, allCw)
        applyMaskZero(modules, reserved)
        writeFormatBitsMaskZeroLevelL(modules)
        return modules
    }

    private fun buildDataCodewords(bytes: ByteArray): IntArray {
        val bits = ArrayList<Int>(DATA_CODEWORDS * 8)
        appendBits(bits, 0b0100, 4)
        appendBits(bits, bytes.size, 8)
        for (b in bytes) {
            appendBits(bits, b.toInt() and 0xFF, 8)
        }
        val capacityBits = DATA_CODEWORDS * 8
        appendBits(bits, 0, minOf(4, capacityBits - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        var padToggle = true
        while (bits.size < capacityBits) {
            appendBits(bits, if (padToggle) 0xEC else 0x11, 8)
            padToggle = !padToggle
        }
        return IntArray(DATA_CODEWORDS) { idx ->
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or bits[idx * 8 + b]
            }
            v
        }
    }

    private fun appendBits(out: MutableList<Int>, value: Int, len: Int) {
        for (i in len - 1 downTo 0) {
            out.add((value ushr i) and 1)
        }
    }

    private fun computeReedSolomon(data: IntArray): IntArray {
        val gen = buildGeneratorPolynomial()
        val rem = IntArray(EC_CODEWORDS)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, EC_CODEWORDS - 1)
            rem[EC_CODEWORDS - 1] = 0
            for (j in 0 until EC_CODEWORDS) {
                rem[j] = rem[j] xor gfMul(gen[j], factor)
            }
        }
        return rem
    }

    private fun buildGeneratorPolynomial(): IntArray {
        var poly = intArrayOf(1)
        var root = 1
        repeat(EC_CODEWORDS) {
            val next = IntArray(poly.size + 1)
            for (i in poly.indices) {
                next[i] = next[i] xor poly[i]
                next[i + 1] = next[i + 1] xor gfMul(poly[i], root)
            }
            poly = next
            root = gfMul(root, 0x02)
        }
        return poly.copyOfRange(1, poly.size)
    }

    private fun gfMul(x: Int, y: Int): Int {
        var res = 0
        var a = x
        var b = y
        repeat(8) {
            if ((b and 1) != 0) res = res xor a
            val hi = a and 0x80
            a = (a shl 1) and 0xFF
            if (hi != 0) a = a xor 0x1D
            b = b ushr 1
        }
        return res
    }

    private fun placeFinderPattern(m: Array<BooleanArray>, r: Array<BooleanArray>, top: Int, left: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val y = top + dy
                val x = left + dx
                if (y !in 0 until SIZE || x !in 0 until SIZE) continue
                val inOuter = dy in 0..6 && dx in 0..6
                val onVertBorder = dy == 0 || dy == 6
                val onHorizBorder = dx == 0 || dx == 6
                val onBorder = onVertBorder || onHorizBorder
                val inCenter = dy in 2..4 && dx in 2..4
                m[y][x] = inOuter && (onBorder || inCenter)
                r[y][x] = true
            }
        }
    }

    private fun placeAlignmentPattern(m: Array<BooleanArray>, r: Array<BooleanArray>, cy: Int, cx: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(kotlin.math.abs(dy), kotlin.math.abs(dx))
                m[cy + dy][cx + dx] = dist != 1
                r[cy + dy][cx + dx] = true
            }
        }
    }

    private fun placeTimingPatterns(m: Array<BooleanArray>, r: Array<BooleanArray>) {
        for (i in 8 until SIZE - 8) {
            if (!r[6][i]) {
                m[6][i] = i % 2 == 0
                r[6][i] = true
            }
            if (!r[i][6]) {
                m[i][6] = i % 2 == 0
                r[i][6] = true
            }
        }
    }

    private fun reserveFormatAreas(m: Array<BooleanArray>, r: Array<BooleanArray>) {
        for (i in 0..8) {
            r[8][i] = true
            r[i][8] = true
        }
        for (i in 0..7) {
            r[8][SIZE - 1 - i] = true
            r[SIZE - 1 - i][8] = true
        }
        m[SIZE - 8][8] = true
        r[SIZE - 8][8] = true
    }

    private fun placeCodewords(m: Array<BooleanArray>, r: Array<BooleanArray>, codewords: IntArray) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = SIZE - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right -= 1
            val rows = if (upward) (SIZE - 1 downTo 0) else (0 until SIZE)
            for (y in rows) {
                for (dx in 0..1) {
                    val x = right - dx
                    if (!r[y][x]) {
                        val dark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx / 8] ushr (7 - (bitIdx % 8))) and 1) != 0
                        } else {
                            false
                        }
                        m[y][x] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun applyMaskZero(m: Array<BooleanArray>, r: Array<BooleanArray>) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (!r[y][x] && (y + x) % 2 == 0) {
                    m[y][x] = !m[y][x]
                }
            }
        }
    }

    private fun writeFormatBitsMaskZeroLevelL(m: Array<BooleanArray>) {
        // Precomputed 15-bit BCH + XOR-masked format word for Error Correction Level L (01) + Mask 0 (000): 0x77C4
        val formatBits = 0x77C4
        for (i in 0..5) m[8][i] = ((formatBits ushr (14 - i)) and 1) != 0
        m[8][7] = ((formatBits ushr 8) and 1) != 0
        m[8][8] = ((formatBits ushr 7) and 1) != 0
        m[7][8] = ((formatBits ushr 6) and 1) != 0
        for (i in 0..5) m[5 - i][8] = ((formatBits ushr (5 - i)) and 1) != 0

        for (i in 0..6) m[SIZE - 1 - i][8] = ((formatBits ushr (14 - i)) and 1) != 0
        for (i in 0..7) m[8][SIZE - 8 + i] = ((formatBits ushr (7 - i)) and 1) != 0
    }
}

/**
 * Hilt binding exposing [OneTimeGrantPrivilegedEngine] under [@OneTimeGrantTier][OneTimeGrantTier].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OneTimeGrantModule {
    @Binds
    @Singleton
    @OneTimeGrantTier
    abstract fun bindOneTimeGrantEngine(impl: OneTimeGrantPrivilegedEngine): PrivilegedEngine
}
