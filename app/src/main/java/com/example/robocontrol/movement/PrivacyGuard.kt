package com.example.robocontrol.movement

/**
 * Decides whether the robot may be somewhere.
 *
 * Design follows the opt-out-by-default position argued in the thesis: a zone
 * marked [PrivacyLevel.PRIVATE] is refused unless someone has explicitly and
 * temporarily lifted the restriction. There is no "allow once and forget" —
 * every override carries a reason, an expiry, and an audit entry, because the
 * literature the thesis draws on identifies *passive, invisible* data-gathering
 * and unexplained movement as the core of the concern. An override that nobody
 * can later see would reproduce exactly that problem.
 *
 * This is enforced in the app, not by the robot. The OrionStar SDK exposes no
 * API for virtual walls or forbidden regions — only the ability to *observe*
 * that the robot has entered an area authored in OrionStar's own map tool
 * ([PoseStatus.FORBIDDEN]). Both are honoured here: SDK-level forbidden areas
 * are treated as hard stops regardless of our own zone model.
 */
class PrivacyGuard(
    zones: MapZones,
    /**
     * Metres of slack added around private zones. The robot is 0.41 m wide and
     * localization drifts, so a zero margin means it is already through the
     * doorway when the test trips.
     */
    var margin: Double = DEFAULT_MARGIN,
    private val clock: () -> Long = System::currentTimeMillis
) {

    var zones: MapZones = zones
        private set

    private var override: Override? = null
    private val auditLog = mutableListOf<AuditEntry>()

    fun updateZones(newZones: MapZones) { zones = newZones }

    // ---- decisions ------------------------------------------------------

    /** Whether the robot may navigate to [target] right now. */
    fun evaluateTarget(target: Point2D): Decision {
        val blocking = zones.zonesAt(target, margin).firstOrNull { it.isPrivate }
            ?: return Decision.Allowed
        return if (overrideActive()) {
            record(AuditEntry.OverriddenEntry(clock(), blocking.name, activeOverride()!!.reason))
            Decision.AllowedByOverride(blocking, activeOverride()!!)
        } else {
            record(AuditEntry.Refused(clock(), blocking.name, target))
            Decision.Refused(blocking)
        }
    }

    /**
     * Whether the robot's *current* pose is somewhere it should not be.
     * Returns null when the position is acceptable.
     *
     * [PoseStatus.FORBIDDEN] is a hard stop: that area was marked forbidden in
     * the robot's own map tool, and an app-level override does not out-rank it.
     */
    fun violationAt(pose: RobotPose): Violation? {
        if (pose.status == PoseStatus.FORBIDDEN) {
            return Violation.SdkForbiddenArea
        }
        val zone = zones.zonesAt(pose.point, margin).firstOrNull { it.isPrivate }
            ?: return null
        if (overrideActive()) return null
        record(AuditEntry.EnteredPrivateZone(clock(), zone.name, pose.point))
        return Violation.PrivateZone(zone)
    }

    /** Convenience: is this named zone currently enterable? */
    fun mayEnter(zoneName: String): Boolean {
        val z = zones.byName(zoneName) ?: return true
        return !z.isPrivate || overrideActive()
    }

    // ---- override -------------------------------------------------------

    /**
     * Temporarily lift private-zone restrictions.
     *
     * The thesis's emergency scenario — the robot has not seen anyone for an
     * interval and goes looking, including in rooms it normally may not enter —
     * is exactly this call with [OverrideReason.EmergencySearch].
     *
     * @param durationMillis hard expiry. There is no indefinite override.
     */
    fun grantOverride(
        reason: OverrideReason,
        durationMillis: Long,
        grantedBy: String
    ): Override {
        require(durationMillis > 0) { "Override must have a positive duration" }
        require(durationMillis <= MAX_OVERRIDE_MILLIS) {
            "Override capped at ${MAX_OVERRIDE_MILLIS / 60000} minutes; re-grant if still needed"
        }
        val o = Override(
            reason = reason,
            grantedBy = grantedBy,
            grantedAt = clock(),
            expiresAt = clock() + durationMillis
        )
        override = o
        record(AuditEntry.OverrideGranted(o.grantedAt, reason, grantedBy, o.expiresAt))
        return o
    }

    fun revokeOverride(revokedBy: String) {
        val o = override ?: return
        override = null
        record(AuditEntry.OverrideRevoked(clock(), o.reason, revokedBy))
    }

    fun overrideActive(): Boolean = activeOverride() != null

    fun activeOverride(): Override? {
        val o = override ?: return null
        if (clock() >= o.expiresAt) {
            override = null
            record(AuditEntry.OverrideExpired(o.expiresAt, o.reason))
            return null
        }
        return o
    }

    // ---- audit ----------------------------------------------------------

    /** Immutable snapshot, newest last. Surface this in the UI — visibility is the point. */
    fun audit(): List<AuditEntry> = auditLog.toList()

    fun clearAudit() = auditLog.clear()

    private fun record(e: AuditEntry) {
        auditLog.add(e)
        if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
    }

    companion object {
        const val DEFAULT_MARGIN = 0.5
        const val MAX_OVERRIDE_MILLIS = 60L * 60L * 1000L // 1 hour
        const val MAX_AUDIT_ENTRIES = 500
    }
}

sealed interface Decision {
    data object Allowed : Decision
    data class AllowedByOverride(val zone: Zone, val override: Override) : Decision
    data class Refused(val zone: Zone) : Decision

    val isAllowed: Boolean get() = this !is Refused
}

sealed interface Violation {
    data class PrivateZone(val zone: Zone) : Violation
    /** Area marked forbidden in OrionStar's map tool. Not overridable from here. */
    data object SdkForbiddenArea : Violation
}

enum class OverrideReason {
    /** No person seen for the configured interval; robot is searching. */
    EmergencySearch,
    /** A person explicitly asked the robot to come. */
    UserSummoned,
    /** Operator/caregiver action. */
    CaregiverRequest,
    /** Developer or bench testing. Should never appear in a deployed run. */
    Maintenance
}

data class Override(
    val reason: OverrideReason,
    val grantedBy: String,
    val grantedAt: Long,
    val expiresAt: Long
)

sealed interface AuditEntry {
    val timestamp: Long

    data class Refused(
        override val timestamp: Long,
        val zoneName: String,
        val target: Point2D
    ) : AuditEntry

    data class EnteredPrivateZone(
        override val timestamp: Long,
        val zoneName: String,
        val at: Point2D
    ) : AuditEntry

    data class OverrideGranted(
        override val timestamp: Long,
        val reason: OverrideReason,
        val grantedBy: String,
        val expiresAt: Long
    ) : AuditEntry

    data class OverrideRevoked(
        override val timestamp: Long,
        val reason: OverrideReason,
        val revokedBy: String
    ) : AuditEntry

    data class OverrideExpired(
        override val timestamp: Long,
        val reason: OverrideReason
    ) : AuditEntry

    data class OverriddenEntry(
        override val timestamp: Long,
        val zoneName: String,
        val reason: OverrideReason
    ) : AuditEntry
}
