package app.routine.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `redacts phone numbers, bearer tokens, key-value secrets, and shell secret flags`() {
        val raw = "Sent SMS to +1 (555) 867-5309 with token=abc123XYZ and --password superSecret Bearer eyJ123.456"
        val redacted = LogRedactor.redact(raw)

        assertFalse(redacted.contains("867-5309"))
        assertFalse(redacted.contains("abc123XYZ"))
        assertFalse(redacted.contains("superSecret"))
        assertFalse(redacted.contains("eyJ123"))
        assertTrue(redacted.contains("[REDACTED_PHONE]"))
        assertTrue(redacted.contains("token=[REDACTED]"))
        assertTrue(redacted.contains("--password [REDACTED]"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `redactConfig masks sensitive keys while preserving safe structural keys`() {
        val config = mapOf(
            "mode" to "DIRECT",
            "message" to "I am driving right now, call me only if urgent",
            "command" to "svc wifi disable",
            "pairing_code" to "482910",
        )
        val safe = LogRedactor.redactConfig(config)
        assertEquals("DIRECT", safe["mode"])
        assertEquals("[REDACTED]", safe["message"])
        assertEquals("[REDACTED]", safe["command"])
        assertEquals("[REDACTED]", safe["pairing_code"])
    }
}
