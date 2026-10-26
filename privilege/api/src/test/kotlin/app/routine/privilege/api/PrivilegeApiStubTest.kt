package app.routine.privilege.api

import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeApiStubTest {
    @Test
    fun `privilege availability states instantiate cleanly`() {
        val state: PrivilegeAvailability = PrivilegeAvailability.AdbBridgeConnected(uid = 2000)
        assertTrue(state is PrivilegeAvailability.AdbBridgeConnected)
    }
}
