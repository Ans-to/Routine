package app.routine.core.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.routine.core.data.DatabaseConstants
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Encrypted Room database storing Routine's rules and capped execution logs.
 */
@Database(
    entities = [RuleEntity::class, LogEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RoutineTypeConverters::class)
abstract class RoutineDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao

    companion object {
        /**
         * Builds the SQLCipher-encrypted [RoutineDatabase] using an Android KeyStore-wrapped 256-bit key.
         */
        fun buildEncrypted(context: Context): RoutineDatabase {
            System.loadLibrary("sqlcipher")
            val passphraseBytes = SqlCipherKeyManager(context).getOrCreatePassphrase()
            val factory = SupportOpenHelperFactory(passphraseBytes)
            val dbFile = context.getDatabasePath(DatabaseConstants.DATABASE_NAME)
            return Room.databaseBuilder(
                context.applicationContext,
                RoutineDatabase::class.java,
                dbFile.absolutePath,
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

/**
 * Generates and unwraps a 256-bit SQLCipher database key protected by AndroidKeyStore AES-256-GCM.
 */
class SqlCipherKeyManager(
    private val context: Context,
) {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOrCreatePassphrase(): ByteArray {
        val masterKey = getOrCreateMasterKey()
        val existingCiphertext = prefs.getString(KEY_WRAPPED_PASSPHRASE, null)
        val existingIv = prefs.getString(KEY_WRAPPED_IV, null)
        if (existingCiphertext != null && existingIv != null) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ivBytes = Base64.decode(existingIv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
            return cipher.doFinal(Base64.decode(existingCiphertext, Base64.NO_WRAP))
        }

        val rawPassphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val wrapped = cipher.doFinal(rawPassphrase)
        prefs.edit()
            .putString(KEY_WRAPPED_PASSPHRASE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(KEY_WRAPPED_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return rawPassphrase
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "routine_sqlcipher_master_key_v1"
        private const val PREFS_NAME = "routine_keystore_wrapped_db"
        private const val KEY_WRAPPED_PASSPHRASE = "wrapped_passphrase"
        private const val KEY_WRAPPED_IV = "wrapped_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
    }
}
