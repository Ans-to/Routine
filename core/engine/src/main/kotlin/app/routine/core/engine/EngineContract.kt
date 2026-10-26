package app.routine.core.engine

/**
 * Marker interface for Routine's UI-independent rule execution engine.
 *
 * Contract: Implementations evaluate trigger conditions, verify capabilities, and execute linear action sequences.
 * Failure modes: Never throws uncaught exceptions for rule failures; captures structured execution results instead.
 * Thread safety: All implementations must be safe for concurrent invocation from arbitrary coroutine dispatchers.
 */
interface EngineContract {
    /**
     * Returns the current engine specification version.
     *
     * Contract: Pure constant accessor returning a positive integer version.
     * Failure modes: Does not fail or throw.
     * Thread safety: Thread-safe.
     */
    fun engineVersion(): Int = 1
}
