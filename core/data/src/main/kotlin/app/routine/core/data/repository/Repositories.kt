package app.routine.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.routine.core.data.DatabaseConstants
import app.routine.core.data.local.LogDao
import app.routine.core.data.local.LogEntity
import app.routine.core.data.local.RuleDao
import app.routine.core.data.local.RuleEntity
import app.routine.core.engine.ExecutionLogSink
import app.routine.core.engine.LogRedactor
import app.routine.core.engine.RuleExecutionStateStore
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.TriggerType
import app.routine.core.model.ValidationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.userPreferencesDataStore by preferencesDataStore(name = "routine_user_prefs")

/**
 * Theme preference options in Settings.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Snapshot of user-configurable app preferences.
 */
data class UserPreferences(
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val wirelessDebuggingAutoReconnect: Boolean = true,
)

/**
 * Repository managing user preferences in Jetpack DataStore.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val preferencesFlow: Flow<UserPreferences> = context.userPreferencesDataStore.data.map { prefs ->
        val mode = prefs[KEY_THEME_MODE]?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
        } ?: ThemeMode.SYSTEM
        UserPreferences(
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            themeMode = mode,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            wirelessDebuggingAutoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.userPreferencesDataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.userPreferencesDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.userPreferencesDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setWirelessDebuggingAutoReconnect(enabled: Boolean) {
        context.userPreferencesDataStore.edit { it[KEY_AUTO_RECONNECT] = enabled }
    }

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("wireless_adb_auto_reconnect")
    }
}

/**
 * Repository persisting [Rule] definitions and implementing [RuleExecutionStateStore].
 */
@Singleton
class RuleRepository @Inject constructor(
    private val ruleDao: RuleDao,
) : RuleExecutionStateStore {
    val allRulesFlow: Flow<List<Rule>> = ruleDao.observeAllRules().map { list ->
        list.map { it.toDomain() }
    }

    val enabledRulesFlow: Flow<List<Rule>> = ruleDao.observeEnabledRules().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getAllRules(): List<Rule> = ruleDao.getAllRules().map { it.toDomain() }

    suspend fun getEnabledRulesForTrigger(type: TriggerType): List<Rule> =
        ruleDao.getEnabledRulesByTrigger(type.name).map { it.toDomain() }

    suspend fun getRuleById(id: String): Rule? = ruleDao.getRuleById(id)?.toDomain()

    suspend fun upsertRule(rule: Rule) {
        when (val validation = rule.validate()) {
            ValidationResult.Valid -> ruleDao.upsertRule(RuleEntity.fromDomain(rule))
            is ValidationResult.Invalid -> {
                throw IllegalArgumentException("Invalid rule '${rule.id}': ${validation.reasons.joinToString()}")
            }
        }
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) {
        ruleDao.setRuleEnabled(id, enabled)
    }

    suspend fun deleteRule(id: String) {
        ruleDao.deleteRuleById(id)
    }

    override suspend fun recordSuccess(ruleId: String, triggeredAtEpochMs: Long) {
        ruleDao.recordSuccess(ruleId, triggeredAtEpochMs)
    }

    override suspend fun recordFailure(rule: Rule): Int =
        ruleDao.incrementFailureAndMaybeDisable(rule.id, Rule.CIRCUIT_BREAKER_THRESHOLD)
}

/**
 * Repository persisting redacted [ExecutionLogEntry] records capped at 1,000 entries.
 */
@Singleton
class LogRepository @Inject constructor(
    private val logDao: LogDao,
) : ExecutionLogSink {
    val allLogsFlow: Flow<List<ExecutionLogEntry>> = logDao.observeAllLogs().map { list ->
        list.map { it.toDomain() }
    }

    fun observeFiltered(
        ruleId: String? = null,
        status: LogStatus? = null,
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
    ): Flow<List<ExecutionLogEntry>> = logDao.observeFilteredLogs(
        ruleId = ruleId?.takeIf { it.isNotBlank() },
        status = status?.name,
        fromEpochMs = fromEpochMs,
        toEpochMs = toEpochMs,
    ).map { list -> list.map { it.toDomain() } }

    override suspend fun append(entry: ExecutionLogEntry) {
        val redacted = entry.copy(message = LogRedactor.redact(entry.message))
        logDao.insertWithRotation(
            entity = LogEntity.fromDomain(redacted),
            maxEntries = DatabaseConstants.MAX_LOG_ENTRIES,
        )
    }

    suspend fun clearLogs() {
        logDao.clearAll()
    }
}

/**
 * Schema-versioned JSON payload for local import/export through the Android system document picker.
 */
@Serializable
data class RoutineBackupPayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val rules: List<Rule>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Serializes and validates [RoutineBackupPayload] JSON files with schema versioning.
 */
object RoutineBackupSerializer {
    private val backupJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportToJson(rules: List<Rule>, exportedAtEpochMs: Long = System.currentTimeMillis()): String =
        backupJson.encodeToString(
            RoutineBackupPayload.serializer(),
            RoutineBackupPayload(
                schemaVersion = RoutineBackupPayload.CURRENT_SCHEMA_VERSION,
                exportedAtEpochMs = exportedAtEpochMs,
                rules = rules,
            ),
        )

    fun importFromJson(rawJson: String): Result<List<Rule>> = runCatching {
        val payload = backupJson.decodeFromString(RoutineBackupPayload.serializer(), rawJson)
        require(payload.schemaVersion in 1..RoutineBackupPayload.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion=${payload.schemaVersion}."
        }
        payload.rules.forEach { rule ->
            when (val validation = rule.validate()) {
                ValidationResult.Valid -> Unit
                is ValidationResult.Invalid -> {
                    throw IllegalArgumentException(
                        "Invalid rule '${rule.name}' in backup: ${validation.reasons.joinToString()}",
                    )
                }
            }
        }
        payload.rules
    }
}
