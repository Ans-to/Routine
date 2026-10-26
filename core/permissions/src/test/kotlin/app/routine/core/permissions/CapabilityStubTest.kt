package app.routine.core.permissions

import org.junit.Assert.assertSame
import org.junit.Test

class CapabilityStubTest {
    @Test
    fun `ready capability is singleton`() {
        assertSame(Capability.Ready, Capability.Ready)
    }
}
