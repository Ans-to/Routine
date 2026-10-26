package app.routine.core.permissions

import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Rule
import app.routine.core.model.Template
import app.routine.core.model.ValidationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates [Rule] and [Template] requirements against the active [PermissionSnapshot].
 */
interface CapabilityChecker {
    /**
     * Evaluates whether [rule] can execute on the device right now.
     *
     * Contract: Validates [rule], refreshes the current [PermissionSnapshot], and returns the resulting [Capability].
     * Failure modes: Does not throw on invalid rules or missing permissions; returns [Capability.Blocked],
     * [Capability.NeedsSetup], or [Capability.RequiresPrivilegeTier] instead.
     * Thread safety: Safe for concurrent calls across arbitrary coroutine dispatchers.
     */
    suspend fun checkRule(rule: Rule): Capability

    /**
     * Evaluates the home-screen capability badge state for [template] against [snapshot].
     *
     * Contract: Pure synchronous evaluation of [template] against a known [PermissionSnapshot].
     * Failure modes: Returns [Capability.Blocked] if [template] fails validation; never throws.
     * Thread safety: Pure stateless evaluation; completely thread-safe.
     */
    fun evaluateTemplate(template: Template, snapshot: PermissionSnapshot): Capability

    /**
     * Evaluates [rule] synchronously against an already-fetched [PermissionSnapshot].
     *
     * Contract: Pure synchronous evaluation of [rule] against [snapshot].
     * Failure modes: Returns [Capability.Blocked] if [rule] fails validation; never throws.
     * Thread safety: Pure stateless evaluation; completely thread-safe.
     */
    fun evaluateRule(rule: Rule, snapshot: PermissionSnapshot): Capability
}

/**
 * Default production implementation of [CapabilityChecker].
 */
@Singleton
class DefaultCapabilityChecker @Inject constructor(
    private val permissionStateProvider: PermissionStateProvider,
) : CapabilityChecker {
    /**
     * Refreshes OS permission state and evaluates [rule].
     *
     * Contract: Delegates to [PermissionStateProvider.refresh] followed by [evaluateRule].
     * Failure modes: Propagates structured [Capability.Blocked] if validation fails; rethrows cancellation.
     * Thread safety: Thread-safe; holds no mutable internal state.
     */
    override suspend fun checkRule(rule: Rule): Capability {
        val snapshot = permissionStateProvider.refresh()
        return evaluateRule(rule, snapshot)
    }

    /**
     * Evaluates [template] against [snapshot] to produce Green, Amber, or Grey badge state.
     *
     * Contract: Validates [template] first, then evaluates its underlying [Rule] and declared [Template.requiredTier].
     * Failure modes: Returns [Capability.Blocked] if [template] is invalid; never throws.
     * Thread safety: Stateless and thread-safe.
     */
    override fun evaluateTemplate(template: Template, snapshot: PermissionSnapshot): Capability {
        when (val validation = template.validate()) {
            ValidationResult.Valid -> Unit
            is ValidationResult.Invalid -> {
                return Capability.Blocked(validation.reasons.joinToString("; "))
            }
        }
        return evaluateRule(template.rule, snapshot)
    }

    /**
     * Evaluates [rule] against [snapshot].
     *
     * Contract: Checks validation, circuit-breaker threshold, privileged/accessibility tier requirements,
     * and standard runtime permissions in priority order.
     * Failure modes: Returns [Capability.Blocked] when invalid or tripped; never throws.
     * Thread safety: Stateless and thread-safe.
     */
    override fun evaluateRule(rule: Rule, snapshot: PermissionSnapshot): Capability {
        when (val validation = rule.validate()) {
            ValidationResult.Valid -> Unit
            is ValidationResult.Invalid -> {
                return Capability.Blocked(validation.reasons.joinToString("; "))
            }
        }

        if (rule.consecutiveFailures >= Rule.CIRCUIT_BREAKER_THRESHOLD) {
            return Capability.Blocked(
                "Circuit breaker tripped after ${rule.consecutiveFailures} consecutive failures.",
            )
        }

        val missing = rule.requiredPermissions()
            .filterNot { snapshot.isSatisfied(it) }
            .toSet()

        if (missing.isEmpty()) {
            return Capability.Ready
        }

        val missingPrivileged = missing.filter { it.tier == PrivilegeTier.PRIVILEGED }.toSet()
        if (missingPrivileged.isNotEmpty()) {
            return Capability.RequiresPrivilegeTier(
                requiredTier = PrivilegeTier.PRIVILEGED,
                missingPermissions = missing,
                reason = "Requires Routine's built-in ADB Bridge or One-Time ADB Grant.",
            )
        }

        val missingAccessibility = missing.filter { it.tier == PrivilegeTier.ACCESSIBILITY }.toSet()
        if (missingAccessibility.isNotEmpty()) {
            return Capability.RequiresPrivilegeTier(
                requiredTier = PrivilegeTier.ACCESSIBILITY,
                missingPermissions = missing,
                reason = "Requires enabling Routine's optional deterministic Accessibility Service.",
            )
        }

        return Capability.NeedsSetup(
            missingPermissions = missing,
            reason = "Missing standard permission(s): ${missing.joinToString { it.name }}",
        )
    }
}
