package app.routine.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.routine.core.data.local.RoutineDatabase
import app.routine.core.data.repository.LogRepository
import app.routine.core.data.repository.RoutineBackupSerializer
import app.routine.core.data.repository.RuleRepository
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
    private lateinit var database: RoutineDatabase
    private lateinit var ruleRepository: RuleRepository
    private lateinit var logRepository: LogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RoutineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleRepository = RuleRepository(database.ruleDao())
        logRepository = LogRepository(database.logDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rule dao supports insert, query, update, circuit breaker auto-disable, and delete`() = runTest {
        val rule = Rule(
            id = "rule-bedtime",
            name = "Bedtime Silence",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "23", "minute" to "0")),
            actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT"))),
            enabled = true,
            cooldownSeconds = 30L,
        )

        // Insert & Query
        ruleRepository.upsertRule(rule)
        val fetched = ruleRepository.getRuleById("rule-bedtime")
        assertNotNull(fetched)
        assertEquals("Bedtime Silence", fetched?.name)
        assertEquals(1, ruleRepository.getEnabledRulesForTrigger(TriggerType.TIME).size)

        // Update
        ruleRepository.upsertRule(rule.copy(name = "Updated Bedtime Silence"))
        assertEquals("Updated Bedtime Silence", ruleRepository.getRuleById("rule-bedtime")?.name)

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

        // Delete
        ruleRepository.deleteRule("rule-bedtime")
        assertNull(ruleRepository.getRuleById("rule-bedtime"))
    }

    @Test
    fun `log dao enforces 1000 entry rotation, filtering, and redaction`() = runTest {
        for (i in 1..1_025) {
            logRepository.append(
                ExecutionLogEntry(
                    ruleId = if (i % 2 == 0) "rule-even" else "rule-odd",
                    ruleName = "Test Rule",
                    status = if (i == 1_025) LogStatus.FAILED else LogStatus.SUCCESS,
                    message = "Entry #$i password=topSecretValue",
                    timestampEpochMs = 1_000_000L + i,
                ),
            )
        }

        assertEquals(DatabaseConstants.MAX_LOG_ENTRIES, database.logDao().countLogs())
        val allLogs = logRepository.allLogsFlow.first()
        assertEquals(1_000, allLogs.size)
        // Newest entry is #1025 and secret was redacted
        assertTrue(allLogs.first().message.contains("Entry #1025"))
        assertFalse(allLogs.first().message.contains("topSecretValue"))
        assertTrue(allLogs.first().message.contains("password=[REDACTED]"))

        // Filter by ruleId + status
        val failedLogs = logRepository.observeFiltered(
            ruleId = "rule-odd",
            status = LogStatus.FAILED,
        ).first()
        assertEquals(1, failedLogs.size)
        assertEquals("rule-odd", failedLogs.single().ruleId)
    }

    @Test
    fun `backup serializer exports and imports versioned JSON and rejects future schema`() {
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
    }
}
