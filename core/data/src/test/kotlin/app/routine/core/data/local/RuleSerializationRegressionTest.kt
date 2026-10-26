package app.routine.core.data.local

import app.routine.core.data.repository.RoutineBackupSerializer
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSerializationRegressionTest {
    @Test
    fun `every TriggerType and ActionType round-trips cleanly through RuleEntity explicit serializers`() {
        TriggerType.entries.forEachIndexed { idx, triggerType ->
            val actionTypes = ActionType.entries
            val sampleActions = listOf(
                Action(
                    type = actionTypes[idx % actionTypes.size],
                    config = mapOf("mode" to "VIBRATE", "levelPercent" to "40", "message" to "Test $idx"),
                ),
                Action(
                    type = actionTypes[(idx + 7) % actionTypes.size],
                    config = mapOf("enabled" to "true", "ssid" to "HomeWiFi"),
                ),
            )
            val original = Rule(
                id = "reg_$idx",
                name = "Regression Rule ${triggerType.name}",
                trigger = Trigger(
                    type = triggerType,
                    config = mapOf("time" to "07:30", "days" to "1,2,3,4,5", "ssid" to "Office"),
                ),
                actions = sampleActions,
                enabled = true,
                cooldownSeconds = 60L,
            )

            val restored = RuleEntity.fromDomain(original).toDomain()
            assertEquals(original, restored)
        }
    }

    @Test
    fun `RoutineBackupSerializer explicit serializer round-trips multi-rule payload without reflection`() {
        val rule = Rule(
            id = "backup_1",
            name = "Backup Test",
            trigger = Trigger(TriggerType.BATTERY_LEVEL, mapOf("thresholdPercent" to "20", "direction" to "BELOW")),
            actions = listOf(Action(ActionType.TOAST, mapOf("message" to "Battery below 20%"))),
            enabled = true,
        )
        val raw = RoutineBackupSerializer.exportToJson(listOf(rule), exportedAtEpochMs = 1_700_000_000_000L)
        val decoded = RoutineBackupSerializer.importFromJson(raw).getOrThrow()
        assertEquals(1, decoded.size)
        assertEquals(rule, decoded.single())
    }
}
