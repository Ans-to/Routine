package app.routine.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStubTest {
    @Test
    fun `engine contract exposes positive version`() {
        val contract = object : EngineContract {}
        assertEquals(1, contract.engineVersion())
    }
}
