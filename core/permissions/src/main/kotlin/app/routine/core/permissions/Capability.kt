package app.routine.core.permissions

import app.routine.core.model.PrivilegeTier
import app.routine.core.model.SystemPermission

/**
 * Capability state machine determining whether a rule or template can run immediately on this device.
 */
sealed interface Capability {
    /**
     * All required permissions and privilege tiers are granted and ready (Green badge).
     */
    data object Ready : Capability

    /**
     * Standard Android runtime or special permissions are still required before activation (Amber badge).
     */
    data class NeedsSetup(
        val missingPermissions: Set<SystemPermission>,
        val reason: String,
    ) : Capability

    /**
     * Requires configuring the built-in Privileged Engine or optional Accessibility Service (Grey badge).
     */
    data class RequiresPrivilegeTier(
        val requiredTier: PrivilegeTier,
        val missingPermissions: Set<SystemPermission>,
        val reason: String,
    ) : Capability

    /**
     * Structurally invalid or blocked by circuit breaker / policy.
     */
    data class Blocked(
        val reason: String,
    ) : Capability
}
