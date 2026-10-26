package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Privilege tiers supported by Routine without any external companion app.
 */
@Serializable
enum class PrivilegeTier {
    /** Works with normal or special Android system permissions only. */
    STANDARD,

    /** Optional deterministic Android AccessibilityService tier for app-foreground detection. */
    ACCESSIBILITY,

    /** Self-contained privilege engine tier (Wireless ADB Bridge or persistent One-Time ADB Grant). */
    PRIVILEGED,
}
