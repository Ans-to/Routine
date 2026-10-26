package app.routine.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import app.routine.core.model.Action
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room entity persisting an automation [Rule].
 */
@Entity(
    tableName = "rules",
    indices = [Index(value = ["triggerType", "enabled"])],
)
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val triggerType: String,
    val triggerJson: String,
    val actionsJson: String,
    val enabled: Boolean,
    val cooldownSeconds: Long,
    val consecutiveFailures: Int,
    val lastTriggeredEpochMs: Long?,
    val templateId: String?,
) {
    fun toDomain(json: Json = RoutineJson): Rule = Rule(
        id = id,
        name = name,
        trigger = json.decodeFromString<Trigger>(triggerJson),
        actions = json.decodeFromString<List<Action>>(actionsJson),
        enabled = enabled,
        cooldownSeconds = cooldownSeconds,
        consecutiveFailures = consecutiveFailures,
        lastTriggeredEpochMs = lastTriggeredEpochMs,
        templateId = templateId,
    )

    companion object {
        val RoutineJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromDomain(rule: Rule, json: Json = RoutineJson): RuleEntity = RuleEntity(
            id = rule.id,
            name = rule.name,
            triggerType = rule.trigger.type.name,
            triggerJson = json.encodeToString(rule.trigger),
            actionsJson = json.encodeToString(rule.actions),
            enabled = rule.enabled,
            cooldownSeconds = rule.cooldownSeconds,
            consecutiveFailures = rule.consecutiveFailures,
            lastTriggeredEpochMs = rule.lastTriggeredEpochMs,
            templateId = rule.templateId,
        )
    }
}

/**
 * Room entity persisting a redacted [ExecutionLogEntry].
 */
@Entity(
    tableName = "execution_logs",
    indices = [
        Index(value = ["timestampEpochMs"]),
        Index(value = ["ruleId"]),
        Index(value = ["status"]),
    ],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ruleId: String,
    val ruleName: String,
    val status: String,
    val message: String,
    val timestampEpochMs: Long,
) {
    fun toDomain(): ExecutionLogEntry = ExecutionLogEntry(
        id = id,
        ruleId = ruleId,
        ruleName = ruleName,
        status = runCatching { LogStatus.valueOf(status) }.getOrDefault(LogStatus.FAILED),
        message = message,
        timestampEpochMs = timestampEpochMs,
    )

    companion object {
        fun fromDomain(entry: ExecutionLogEntry): LogEntity = LogEntity(
            id = entry.id,
            ruleId = entry.ruleId,
            ruleName = entry.ruleName,
            status = entry.status.name,
            message = entry.message,
            timestampEpochMs = entry.timestampEpochMs,
        )
    }
}

/**
 * Room type converters for enum helpers.
 */
class RoutineTypeConverters {
    @TypeConverter
    fun fromTriggerType(type: TriggerType): String = type.name

    @TypeConverter
    fun toTriggerType(raw: String): TriggerType = TriggerType.valueOf(raw)
}
