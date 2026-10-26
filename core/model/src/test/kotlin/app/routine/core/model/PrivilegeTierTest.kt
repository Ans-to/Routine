package app.routine.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeTierTest {
    @Test
    fun `privilege tiers cover standard, accessibility, and privileged`() {
        assertEquals(3, PrivilegeTier.entries.size)
    }
}
