package app.routine.privilege.adb.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin implementation of BoringSSL's SPAKE2 over Ed25519 + HKDF-SHA256 + AES-128-GCM
 * used by Android 11+ Wireless Debugging (`_adb-tls-pairing._tcp`).
 */
class AdbSpake2Session(
    val role: Role,
    passwordBytes: ByteArray,
    random: SecureRandom = SecureRandom(),
) {
    enum class Role(val idBytes: ByteArray) {
        CLIENT("adb pair client\u0000".encodeToByteArray()),
        SERVER("adb pair server\u0000".encodeToByteArray()),
    }

    private val privateScalar: ByteArray
    private val passwordScalarBytes: ByteArray
    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(passwordBytes)
    val publicMessage: ByteArray

    init {
        val rawRand = ByteArray(32).also { random.nextBytes(it) }
        val rawInt = BigInteger(1, rawRand.reversedArray()).mod(Ed25519Math.GROUP_ORDER_L)
        privateScalar = Ed25519Math.leftShift3(Ed25519Math.toLittleEndian32(rawInt))
        passwordScalarBytes = Ed25519Math.computeBoringSslPasswordScalar(passwordHash)

        val xBase = Ed25519Math.scalarMultBase(privateScalar)
        val maskGenerator = if (role == Role.CLIENT) Ed25519Math.POINT_M else Ed25519Math.POINT_N
        val maskTerm = Ed25519Math.scalarMult(maskGenerator, passwordScalarBytes)
        publicMessage = Ed25519Math.encodePoint(Ed25519Math.addPoints(xBase, maskTerm))
    }

    /**
     * Processes the peer's 32-byte SPAKE2 message and derives an authenticated [AdbPairingCipher].
     */
    fun processPeerMessage(peerMessage: ByteArray): AdbPairingCipher {
        require(peerMessage.size == 32) { "SPAKE2 peer message must be 32 bytes." }
        val peerPoint = Ed25519Math.decodePoint(peerMessage)
        val peerMaskGen = if (role == Role.CLIENT) Ed25519Math.POINT_N else Ed25519Math.POINT_M
        val peerMask = Ed25519Math.scalarMult(peerMaskGen, passwordScalarBytes)
        val unmasked = Ed25519Math.subtractPoints(peerPoint, peerMask)
        val sharedPoint = Ed25519Math.scalarMult(unmasked, privateScalar)
        val kEncoded = Ed25519Math.encodePoint(sharedPoint)

        val clientMsg = if (role == Role.CLIENT) publicMessage else peerMessage
        val serverMsg = if (role == Role.CLIENT) peerMessage else publicMessage

        val transcript = MessageDigest.getInstance("SHA-512").apply {
            updateLengthPrefixed(Role.CLIENT.idBytes)
            updateLengthPrefixed(Role.SERVER.idBytes)
            updateLengthPrefixed(clientMsg)
            updateLengthPrefixed(serverMsg)
            updateLengthPrefixed(kEncoded)
            updateLengthPrefixed(passwordHash)
        }.digest()

        val aesKey = hkdfSha256(
            ikm = transcript,
            info = HKDF_INFO,
            outLength = 16,
        )
        return AdbPairingCipher(aesKey)
    }

    private fun MessageDigest.updateLengthPrefixed(data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array()
        update(lenBuf)
        update(data)
    }

    companion object {
        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".encodeToByteArray()

        internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, outLength: Int): ByteArray {
            val salt = ByteArray(32)
            val prkMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(salt, "HmacSHA256"))
            }
            val prk = prkMac.doFinal(ikm)
            val expandMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(prk, "HmacSHA256"))
            }
            expandMac.update(info)
            expandMac.update(0x01.toByte())
            return expandMac.doFinal().copyOf(outLength)
        }
    }
}

/**
 * Stateful AES-128-GCM cipher using AOSP `PairingAuthCtx`'s 12-byte little-endian sequence IV.
 */
class AdbPairingCipher(private val keyBytes: ByteArray) {
    private var encryptSequence: Long = 0L
    private var decryptSequence: Long = 0L

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = buildNonce(encryptSequence++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val iv = buildNonce(decryptSequence++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun buildNonce(seq: Long): ByteArray {
        val iv = ByteArray(12)
        ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(seq)
        return iv
    }

    companion object {
        private const val PEER_INFO_SIZE = 8192
        private const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0

        fun buildPeerInfoPayload(adbPublicKeyLine: ByteArray): ByteArray {
            require(adbPublicKeyLine.size <= PEER_INFO_SIZE - 1) { "ADB public key exceeds PeerInfo frame." }
            return ByteArray(PEER_INFO_SIZE).also { frame ->
                frame[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY
                adbPublicKeyLine.copyInto(frame, destinationOffset = 1)
            }
        }
    }
}

/**
 * Pure-Kotlin twisted Edwards Ed25519 group math matching BoringSSL `spake25519.c`.
 */
internal object Ed25519Math {
    val PRIME_P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val GROUP_ORDER_L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private val TWO = BigInteger.valueOf(2)
    private val P_PLUS_3_DIV_8 = PRIME_P.add(BigInteger.valueOf(3)).shiftRight(3)
    private val SQRT_MINUS_ONE = TWO.modPow(PRIME_P.subtract(BigInteger.ONE).shiftRight(2), PRIME_P)
    val D_CONST: BigInteger = BigInteger.valueOf(-121665)
        .mod(PRIME_P)
        .multiply(BigInteger.valueOf(121666).modInverse(PRIME_P))
        .mod(PRIME_P)

    data class ExtPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    val NEUTRAL = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    val BASE_POINT: ExtPoint by lazy {
        val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(PRIME_P)).mod(PRIME_P)
        val bx = recoverX(by, signBit = 0) ?: error("Invalid Ed25519 base point")
        ExtPoint(bx, by, BigInteger.ONE, bx.multiply(by).mod(PRIME_P))
    }

    val POINT_M: ExtPoint by lazy { deriveSeedPoint("ed25519 point generation seed (M)".encodeToByteArray()) }
    val POINT_N: ExtPoint by lazy { deriveSeedPoint("ed25519 point generation seed (N)".encodeToByteArray()) }

    private fun deriveSeedPoint(seed: ByteArray): ExtPoint {
        var current = MessageDigest.getInstance("SHA-256").digest(seed)
        repeat(64) {
            val candidate = tryDecodePointOrNull(current)
            if (candidate != null) {
                // Multiply by cofactor 8 so M and N lie in the prime-order subgroup
                return doublePoint(doublePoint(doublePoint(candidate)))
            }
            current = MessageDigest.getInstance("SHA-256").digest(current)
        }
        error("Failed to derive SPAKE2 generator point")
    }

    fun leftShift3(le32: ByteArray): ByteArray {
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val v = ((le32[i].toInt() and 0xFF) shl 3) or carry
            out[i] = (v and 0xFF).toByte()
            carry = v ushr 8
        }
        return out
    }

    fun computeBoringSslPasswordScalar(sha512Digest: ByteArray): ByteArray {
        var pw = BigInteger(1, sha512Digest.reversedArray()).mod(GROUP_ORDER_L)
        if (pw.and(BigInteger.valueOf(7)) != BigInteger.ZERO) {
            var k = 1
            while (k <= 4) {
                val candidate = pw.add(GROUP_ORDER_L.multiply(BigInteger.valueOf(k.toLong())))
                if (candidate.and(BigInteger.valueOf(7)) == BigInteger.ZERO) {
                    pw = candidate
                    break
                }
                k += 1
            }
            if (pw.and(BigInteger.valueOf(7)) != BigInteger.ZERO) {
                for (mult in 1..7) {
                    val candidate = pw.add(GROUP_ORDER_L.multiply(BigInteger.valueOf(mult.toLong())))
                    if (candidate.and(BigInteger.valueOf(7)) == BigInteger.ZERO) {
                        pw = candidate
                        break
                    }
                }
            }
        }
        return toLittleEndian32(pw)
    }

    fun toLittleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(32)
        var outIdx = 0
        for (i in be.size - 1 downTo 0) {
            if (outIdx >= 32) break
            out[outIdx++] = be[i]
        }
        return out
    }

    fun scalarMultBase(scalarLe: ByteArray): ExtPoint = scalarMult(BASE_POINT, scalarLe)

    fun scalarMult(point: ExtPoint, scalarLe: ByteArray): ExtPoint {
        var result = NEUTRAL
        var addend = point
        for (byteIndex in 0 until 32) {
            val b = scalarLe[byteIndex].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (((b ushr bit) and 1) == 1) {
                    result = addPoints(result, addend)
                }
                addend = doublePoint(addend)
            }
        }
        return result
    }

    fun addPoints(p1: ExtPoint, p2: ExtPoint): ExtPoint {
        val a = p1.y.subtract(p1.x).mod(PRIME_P).multiply(p2.y.subtract(p2.x).mod(PRIME_P)).mod(PRIME_P)
        val b = p1.y.add(p1.x).mod(PRIME_P).multiply(p2.y.add(p2.x).mod(PRIME_P)).mod(PRIME_P)
        val c = TWO.multiply(D_CONST).mod(PRIME_P).multiply(p1.t).mod(PRIME_P).multiply(p2.t).mod(PRIME_P)
        val d = TWO.multiply(p1.z).mod(PRIME_P).multiply(p2.z).mod(PRIME_P)
        val e = b.subtract(a).mod(PRIME_P)
        val f = d.subtract(c).mod(PRIME_P)
        val g = d.add(c).mod(PRIME_P)
        val h = b.add(a).mod(PRIME_P)
        return ExtPoint(
            x = e.multiply(f).mod(PRIME_P),
            y = g.multiply(h).mod(PRIME_P),
            z = f.multiply(g).mod(PRIME_P),
            t = e.multiply(h).mod(PRIME_P),
        )
    }

    fun subtractPoints(p1: ExtPoint, p2: ExtPoint): ExtPoint {
        val negated = ExtPoint(
            x = PRIME_P.subtract(p2.x).mod(PRIME_P),
            y = p2.y,
            z = p2.z,
            t = PRIME_P.subtract(p2.t).mod(PRIME_P),
        )
        return addPoints(p1, negated)
    }

    fun doublePoint(p: ExtPoint): ExtPoint = addPoints(p, p)

    fun encodePoint(p: ExtPoint): ByteArray {
        val zInv = p.z.modInverse(PRIME_P)
        val x = p.x.multiply(zInv).mod(PRIME_P)
        val y = p.y.multiply(zInv).mod(PRIME_P)
        val encoded = toLittleEndian32(y)
        if (x.testBit(0)) {
            encoded[31] = (encoded[31].toInt() or 0x80).toByte()
        }
        return encoded
    }

    fun decodePoint(bytes: ByteArray): ExtPoint =
        tryDecodePointOrNull(bytes) ?: throw IllegalArgumentException("Invalid Ed25519 encoded point.")

    private fun tryDecodePointOrNull(bytes: ByteArray): ExtPoint? {
        if (bytes.size != 32) return null
        val copy = bytes.copyOf()
        val signBit = (copy[31].toInt() ushr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, copy.reversedArray())
        if (y >= PRIME_P) return null
        val x = recoverX(y, signBit) ?: return null
        return ExtPoint(x, y, BigInteger.ONE, x.multiply(y).mod(PRIME_P))
    }

    private fun recoverX(y: BigInteger, signBit: Int): BigInteger? {
        val y2 = y.multiply(y).mod(PRIME_P)
        val u = y2.subtract(BigInteger.ONE).mod(PRIME_P)
        val v = D_CONST.multiply(y2).add(BigInteger.ONE).mod(PRIME_P)
        val x2 = u.multiply(v.modInverse(PRIME_P)).mod(PRIME_P)
        if (x2 == BigInteger.ZERO) {
            return if (signBit == 0) BigInteger.ZERO else null
        }
        var x = x2.modPow(P_PLUS_3_DIV_8, PRIME_P)
        if (x.multiply(x).mod(PRIME_P) != x2) {
            x = x.multiply(SQRT_MINUS_ONE).mod(PRIME_P)
        }
        if (x.multiply(x).mod(PRIME_P) != x2) return null
        if ((if (x.testBit(0)) 1 else 0) != signBit) {
            x = PRIME_P.subtract(x)
        }
        return x
    }
}
