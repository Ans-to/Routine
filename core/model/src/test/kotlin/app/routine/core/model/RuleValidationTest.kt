package app.routine.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {
    private val validTimeTrigger = Trigger(
        type = TriggerType.TIME,
        config = mapOf("hour" to "22", "minute" to "30"),
    )

    private val validNotifyAction = Action(
        type = ActionType.NOTIFY,
        config = mapOf("title" to "Bedtime", "message" to "Time to wind down"),
    )

    @Test
    fun `valid rule with 1 to 3 actions passes validation`() {
        val rule = Rule(
            id = "rule-1",
            name = "Bedtime Reminder",
            trigger = validTimeTrigger,
            actions = listOf(
                validNotifyAction,
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true")),
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "15")),
            ),
            cooldownSeconds = 60L,
        )
        assertEquals(ValidationResult.Valid, rule.validate())
        assertEquals(PrivilegeTier.STANDARD, rule.requiredTier)
    }

    @Test
    fun `rule with zero or more than three actions fails validation`() {
        val zeroActions = Rule(
            id = "rule-0",
            name = "Empty",
            trigger = validTimeTrigger,
            actions = emptyList(),
        )
        assertTrue(zeroActions.validate() is ValidationResult.Invalid)

        val fourActions = zeroActions.copy(
            actions = List(4) { validNotifyAction },
        )
        val result = fourActions.validate()
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).reasons.any { it.contains("at most 3") })
    }

    @Test
    fun `rule rejects blank id, blank name, negative cooldown, and invalid trigger config`() {
        val invalidRule = Rule(
            id = "",
            name = "   ",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "25", "minute" to "-1")),
            actions = listOf(Action(ActionType.BRIGHTNESS, mapOf("percent" to "150"))),
            cooldownSeconds = -5L,
            consecutiveFailures = -1,
        )
        val validation = invalidRule.validate() as ValidationResult.Invalid
        assertEquals(7, validation.reasons.size)
    }

    @Test
    fun `requiredTier escalates to PRIVILEGED when any action is privileged`() {
        val rule = Rule(
            id = "wifi-off",
            name = "Wi-Fi Off at Bedtime",
            trigger = validTimeTrigger,
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false"))),
        )
        assertEquals(PrivilegeTier.PRIVILEGED, rule.requiredTier)
        assertTrue(SystemPermission.ADB_SHELL_BRIDGE in rule.requiredPermissions())
    }

    @Test
    fun `template validates metadata and nested rule`() {
        val rule = Rule(
            id = "tpl-rule",
            name = "Unplug at 80%",
            trigger = Trigger(TriggerType.BATTERY_LEVEL, mapOf("thresholdPercent" to "80", "direction" to "ABOVE")),
            actions = listOf(validNotifyAction),
        )
        val template = Template(
            id = "unplug-80",
            title = "Unplug at 80%",
            description = "Protect battery longevity by alerting at 80% charge.",
            iconName = "battery_charging_full",
            rule = rule,
            requiredTier = PrivilegeTier.STANDARD,
        )
        assertEquals(ValidationResult.Valid, template.validate())

        val invalidTemplate = template.copy(title = "")
        assertTrue(invalidTemplate.validate() is ValidationResult.Invalid)
    }

    @Test
    fun `dynamic permissions adjust for mobile data check and manual calendar mode`() {
        val dataRule = Rule(
            id = "data-alert",
            name = "Data Limit Alert",
            trigger = Trigger(TriggerType.PERIODIC_CHECK, mapOf("metric" to "MOBILE_DATA_MB", "threshold" to "5000")),
            actions = listOf(validNotifyAction),
        )
        assertTrue(SystemPermission.PACKAGE_USAGE_STATS in dataRule.requiredPermissions())

        val manualRule = Rule(
            id = "manual-dnd",
            name = "Manual Meeting DND",
            trigger = Trigger(TriggerType.CALENDAR_OR_MANUAL, mapOf("mode" to "MANUAL")),
            actions = listOf(Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true"))),
        )
        assertTrue(SystemPermission.READ_CALENDAR !in manualRule.requiredPermissions())
    }
}
