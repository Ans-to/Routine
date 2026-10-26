package app.routine.action.privileged

import app.routine.core.model.PrivilegeTier

/**
 * Metadata descriptor for privileged-tier actions backed exclusively by [app.routine.privilege.api].
 */
object PrivilegedActionModule {
    val defaultTier: PrivilegeTier = PrivilegeTier.PRIVILEGED
}
