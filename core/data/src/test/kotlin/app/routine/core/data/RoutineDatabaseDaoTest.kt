package app.routine.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.routine.core.data.local.LogEntity
import app.routine.core.data.local.RoutineDatabase
import app.routine.core.data.local.RoutineTypeConverters
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RoutineBackupSerializer
import app.routine.core.data.repository.RuleRepository
import app.routine.core.data.repository.ThemeMode
import app.routine.core.data.repository.UserPreferencesRepository
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.ExecutionLogEntry
import app.routine.core.model.LogStatus
import app.routine.core.model.Rule
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoutineDatabaseDaoTest {
    private lateinit var context: Context
    private lateinit var database: RoutineDatabase
    private lateinit var ruleRepository: RuleRepository
    private lateinit var logRepository: LogRepository
    private lateinit var preferencesRepository: UserPreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RoutineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleRepository = RuleRepository(database.ruleDao())
        logRepository = LogRepository(database.logDao())
        preferencesRepository = UserPreferencesRepository(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rule dao supports insert, flows, success reset, circuit breaker auto-disable, and delete`() = runTest {
        val rule = Rule(
            id = "rule-bedtime",
            name = "Bedtime Silence",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "23", "minute" to "0")),
            actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT"))),
            enabled = true,
            cooldownSeconds = 30L,
        )

        ruleRepository.upsertRule(rule)
        val fetched = ruleRepository.getRuleById("rule-bedtime")
        assertNotNull(fetched)
        assertEquals("Bedtime Silence", fetched?.name)
        assertEquals(1, ruleRepository.getAllRules().size)
        assertEquals(1, ruleRepository.allRulesFlow.first().size)
        assertEquals(1, ruleRepository.enabledRulesFlow.first().size)
        assertEquals(1, ruleRepository.getEnabledRulesForTrigger(TriggerType.TIME).size)

        // Update and recordSuccess
        ruleRepository.upsertRule(rule.copy(name = "Updated Bedtime Silence"))
        ruleRepository.recordSuccess("rule-bedtime", triggeredAtEpochMs = 55_000L)
        assertEquals(55_000L, ruleRepository.getRuleById("rule-bedtime")?.lastTriggeredEpochMs)

        // Consecutive failures auto-disable at threshold 5
        repeat(4) {
            val count = ruleRepository.recordFailure(rule)
            assertEquals(it + 1, count)
            assertTrue(ruleRepository.getRuleById("rule-bedtime")!!.enabled)
        }
        val fifthCount = ruleRepository.recordFailure(rule)
        assertEquals(5, fifthCount)
        assertFalse(ruleRepository.getRuleById("rule-bedtime")!!.enabled)

        // Re-enabling resets consecutiveFailures to 0
        ruleRepository.setRuleEnabled("rule-bedtime", true)
        val resetRule = ruleRepository.getRuleById("rule-bedtime")!!
        assertTrue(resetRule.enabled)
        assertEquals(0, resetRule.consecutiveFailures)

        // Invalid rule rejected by upsertRule
        val invalid = rule.copy(id = "bad", actions = emptyList())
        val invalidRes = runCatching { ruleRepository.upsertRule(invalid) }
        assertTrue(invalidRes.isFailure)

        // Delete
        ruleRepository.deleteRule("rule-bedtime")
        assertNull(ruleRepository.getRuleById("rule-bedtime"))
    }

    @Test
    fun `log dao enforces 1000 entry rotation, filtering, clear, and SMS or shell secret redaction`() = runTest {
        for (i in 1..1_025) {
            logRepository.append(
                ExecutionLogEntry(
                    ruleId = if (i % 2 == 0) "rule-even" else "rule-odd",
                    ruleName = "Test Rule",
                    status = if (i == 1_025) LogStatus.FAILED else LogStatus.SUCCESS,
                    message = "Entry #$i password=topSecretValue sms=PrivateBody +1-555-867-5309 " +
                        "settings put secure night_display_activated 1",
                    timestampEpochMs = 1_000_000L + i,
                ),
            )
        }

        assertEquals(DatabaseConstants.MAX_LOG_ENTRIES, database.logDao().countLogs())
        val allLogs = logRepository.allLogsFlow.first()
        assertEquals(1_000, allLogs.size)
        val newest = allLogs.first().message
        assertTrue(newest.contains("Entry #1025"))
        assertFalse(newest.contains("topSecretValue"))
        assertFalse(newest.contains("PrivateBody"))
        assertFalse(newest.contains("867-5309"))
        assertTrue(newest.contains("password=[REDACTED]"))
        assertTrue(newest.contains("sms=[REDACTED]"))
        assertTrue(newest.contains("[REDACTED_PHONE]"))
        assertTrue(newest.contains("settings put secure night_display_activated [REDACTED]"))

        val failedLogs = logRepository.observeFiltered(
            ruleId = "rule-odd",
            status = LogStatus.FAILED,
        ).first()
        assertEquals(1, failedLogs.size)

        logRepository.clearLogs()
        assertEquals(0, database.logDao().countLogs())

        // Verify LogEntity fallback on unknown status & RoutineTypeConverters
        val fallbackDomain = LogEntity(
            ruleId = "r1",
            ruleName = "R1",
            status = "UNKNOWN_STATUS",
            message = "msg",
            timestampEpochMs = 1L,
        ).toDomain()
        assertEquals(LogStatus.FAILED, fallbackDomain.status)
        val converters = RoutineTypeConverters()
        assertEquals(TriggerType.CHARGER, converters.toTriggerType(converters.fromTriggerType(TriggerType.CHARGER)))
    }

    @Test
    fun `user preferences repository persists onboarding, theme, dynamic color, and auto reconnect`() = runTest {
        preferencesRepository.setOnboardingCompleted(true)
        preferencesRepository.setThemeMode(ThemeMode.DARK)
        preferencesRepository.setDynamicColor(false)
        preferencesRepository.setWirelessDebuggingAutoReconnect(false)

        val updated = preferencesRepository.preferencesFlow.first()
        assertTrue(updated.onboardingCompleted)
        assertEquals(ThemeMode.DARK, updated.themeMode)
        assertFalse(updated.dynamicColor)
        assertFalse(updated.wirelessDebuggingAutoReconnect)
    }

    @Test
    fun `backup serializer exports and imports versioned JSON and rejects future schema or invalid rule`() {
        val sampleRule = Rule(
            id = "backup-1",
            name = "Unplug at 80%",
            trigger = Trigger(TriggerType.BATTERY_LEVEL, mapOf("thresholdPercent" to "80", "direction" to "ABOVE")),
            actions = listOf(Action(ActionType.NOTIFY, mapOf("title" to "Battery", "message" to "Unplug charger"))),
        )
        val json = RoutineBackupSerializer.exportToJson(listOf(sampleRule), exportedAtEpochMs = 123456L)
        val imported = RoutineBackupSerializer.importFromJson(json).getOrThrow()
        assertEquals(listOf(sampleRule), imported)

        val futureSchemaJson = """{"schemaVersion":99,"exportedAtEpochMs":0,"rules":[]}"""
        assertTrue(RoutineBackupSerializer.importFromJson(futureSchemaJson).isFailure)

        val invalidRuleJson = """
            {
              "schemaVersion": 1,
              "exportedAtEpochMs": 0,
              "rules": [
                {
                  "id": "invalid-1",
                  "name": "",
                  "trigger": { "type": "CHARGER", "config": {} },
                  "actions": []
                }
              ]
            }
        """.trimIndent()
        assertTrue(RoutineBackupSerializer.importFromJson(invalidRuleJson).isFailure)
    }
}
