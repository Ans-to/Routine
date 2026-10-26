package app.routine.core.permissions

/**
 * Represents whether the current device configuration satisfies the requirements to execute a rule or template.
 */
sealed interface Capability {
    /**
     * All required permissions and privilege tiers are active and ready for immediate execution.
     */
    data object Ready : Capability
}
