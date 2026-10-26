package app.routine.privilege.adb.crypto

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Properties
import javax.crypto.Cipher

/**
 * Manages Routine's persistent 2048-bit ADB RSA identity, 524-byte `RSAPublicKey` struct encoding,
 * SHA-1 `AUTH_TOKEN` PKCS#1 signature, and pure-Kotlin ASN.1 DER self-signed X.509 TLS certificate.
 */
class AdbRsaKeyManager(private val storageDir: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "adb_identity"))

    private val keyPair: KeyPair by lazy { loadOrGenerateKeyPair() }
    val certificate: X509Certificate by lazy { generateSelfSignedX509Certificate(keyPair) }

    val privateKey: RSAPrivateKey
        get() = keyPair.private as RSAPrivateKey

    val publicKey: RSAPublicKey
        get() = keyPair.public as RSAPublicKey

    /**
     * Returns `true` when Routine has already generated and stored its persistent ADB RSA keypair.
     */
    fun hasPersistedIdentity(): Boolean = File(storageDir, "adb_rsa_key.properties").exists()

    /**
     * Formats the exact `RSAPUBLICKEY` / `PeerInfo` payload expected by Android's `adbd`.
     */
    fun buildAdbPublicKeyPayload(comment: String = "routine@localhost"): ByteArray {
        val structBytes = encodeAndroidRsaPublicKeyStruct(publicKey)
        val b64 = Base64.encodeToString(structBytes, Base64.NO_WRAP)
        return "$b64 $comment\u0000".encodeToByteArray()
    }

    /**
     * Signs a 20-byte `AUTH_TOKEN` challenge using AOSP's SHA-1 DigestInfo PKCS#1 v1.5 format.
     */
    fun signAuthToken(token20Bytes: ByteArray): ByteArray {
        val digestInfo = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token20Bytes.size).also { out ->
            SHA1_DIGEST_INFO_PREFIX.copyInto(out, 0)
            token20Bytes.copyInto(out, SHA1_DIGEST_INFO_PREFIX.size)
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(digestInfo)
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        storageDir.mkdirs()
        val propFile = File(storageDir, "adb_rsa_key.properties")
        if (propFile.exists()) {
            val loaded = runCatching {
                val props = Properties().apply { propFile.inputStream().use { load(it) } }
                val privBytes = Base64.decode(props.getProperty("priv"), Base64.NO_WRAP)
                val pubBytes = Base64.decode(props.getProperty("pub"), Base64.NO_WRAP)
                val kf = KeyFactory.getInstance("RSA")
                KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pubBytes)),
                    kf.generatePrivate(PKCS8EncodedKeySpec(privBytes)),
                )
            }.getOrNull()
            if (loaded != null) return loaded
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        return gen.generateKeyPair().also { generated ->
            val props = Properties().apply {
                setProperty("priv", Base64.encodeToString(generated.private.encoded, Base64.NO_WRAP))
                setProperty("pub", Base64.encodeToString(generated.public.encoded, Base64.NO_WRAP))
            }
            propFile.outputStream().use { props.store(it, "Routine Local ADB Identity") }
        }
    }

    companion object {
        private const val KEY_WORDS = 64
        const val ANDROID_RSA_PUBKEY_STRUCT_BYTES: Int = 4 + 4 + (KEY_WORDS * 4) + (KEY_WORDS * 4) + 4

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14,
        )

        /**
         * Encodes an [RSAPublicKey] into Android `libcrypto_utils`'s 524-byte `RSAPublicKey` binary struct.
         */
        fun encodeAndroidRsaPublicKeyStruct(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            val wordBase = BigInteger.ONE.shiftLeft(32)
            val r = BigInteger.ONE.shiftLeft(2048)
            val rr = r.multiply(r).mod(n)
            val n0inv = n.mod(wordBase).modInverse(wordBase).negate().mod(wordBase).toLong()

            val buffer = ByteBuffer.allocate(ANDROID_RSA_PUBKEY_STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(KEY_WORDS)
            buffer.putInt(n0inv.toInt())

            var remN = n
            repeat(KEY_WORDS) {
                val (quot, rem) = remN.divideAndRemainder(wordBase)
                buffer.putInt(rem.toLong().toInt())
                remN = quot
            }

            var remRR = rr
            repeat(KEY_WORDS) {
                val (quot, rem) = remRR.divideAndRemainder(wordBase)
                buffer.putInt(rem.toLong().toInt())
                remRR = quot
            }

            buffer.putInt(pub.publicExponent.toInt())
            return buffer.array()
        }

        /**
         * Builds a standards-compliant self-signed X.509v3 certificate in pure Kotlin (SHA256withRSA)
         * without requiring BouncyCastle.
         */
        fun generateSelfSignedX509Certificate(keyPair: KeyPair): X509Certificate {
            val sha256WithRsaAlgId = derSeq(
                derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)),
                derNull(),
            )
            val cnRoutine = derSeq(
                derSet(
                    derSeq(
                        derOid(intArrayOf(2, 5, 4, 3)),
                        derUtf8String("Routine Local ADB"),
                    ),
                ),
            )
            val validity = derSeq(
                derUtcTime("250101000000Z"),
                derUtcTime("491231235959Z"),
            )
            val tbsCert = derSeq(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(BigInteger.valueOf(1_000_001L)),
                sha256WithRsaAlgId,
                cnRoutine,
                validity,
                cnRoutine,
                keyPair.public.encoded,
            )
            val sigBytes = Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(tbsCert)
                sign()
            }
            val certDer = derSeq(
                tbsCert,
                sha256WithRsaAlgId,
                derBitString(sigBytes),
            )
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        }

        private fun derSeq(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
        private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
        private fun derExplicit(tagNo: Int, inner: ByteArray): ByteArray = derTag(0xA0 or tagNo, inner)
        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
        private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
        private fun derUtf8String(text: String): ByteArray = derTag(0x0C, text.encodeToByteArray())
        private fun derUtcTime(ascii: String): ByteArray = derTag(0x17, ascii.encodeToByteArray())

        private fun derBitString(bytes: ByteArray): ByteArray {
            val content = ByteArray(bytes.size + 1)
            content[0] = 0x00
            bytes.copyInto(content, 1)
            return derTag(0x03, content)
        }

        private fun derOid(nodes: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(nodes[0] * 40 + nodes[1])
            for (i in 2 until nodes.size) {
                writeBase128(out, nodes[i])
            }
            return derTag(0x06, out.toByteArray())
        }

        private fun writeBase128(out: ByteArrayOutputStream, value: Int) {
            val bytes = ArrayList<Int>(5)
            var v = value
            bytes.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                bytes.add((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            for (i in bytes.size - 1 downTo 0) out.write(bytes[i])
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val len = content.size
            if (len < 128) {
                out.write(len)
            } else {
                val lenBytes = ArrayList<Int>(4)
                var rem = len
                while (rem > 0) {
                    lenBytes.add(rem and 0xFF)
                    rem = rem ushr 8
                }
                out.write(0x80 or lenBytes.size)
                for (i in lenBytes.size - 1 downTo 0) out.write(lenBytes[i])
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun concat(vararg arrays: ByteArray): ByteArray {
            val total = arrays.sumOf { it.size }
            val out = ByteArray(total)
            var pos = 0
            for (arr in arrays) {
                arr.copyInto(out, pos)
                pos += arr.size
            }
            return out
        }
    }
}
