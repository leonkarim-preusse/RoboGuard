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

    /** Per-zone overrides, keyed by lower-case zone name: lift the restriction for ONE area only. */
    private val zoneOverrides = mutableMapOf<String, Override>()
    private val auditLog = mutableListOf<AuditEntry>()

    fun updateZones(newZones: MapZones) { zones = newZones }

    // ---- decisions ------------------------------------------------------

    /** Whether the robot may navigate to [target] right now. */
    fun evaluateTarget(target: Point2D): Decision {
        val private = zones.zonesAt(target, margin).filter { it.isPrivate }
        if (private.isEmpty()) return Decision.Allowed
        val blocking = private.firstOrNull { overrideFor(it) == null }
        if (blocking != null) {
            record(AuditEntry.Refused(clock(), blocking.name, target))
            return Decision.Refused(blocking)
        }
        val zone = private.first()
        val o = overrideFor(zone)!!
        record(AuditEntry.OverriddenEntry(clock(), zone.name, o.reason))
        return Decision.AllowedByOverride(zone, o)
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
        val zone = zones.zonesAt(pose.point, margin).firstOrNull { it.isPrivate && overrideFor(it) == null }
            ?: return null
        record(AuditEntry.EnteredPrivateZone(clock(), zone.name, pose.point))
        return Violation.PrivateZone(zone)
    }

    /** Convenience: is this named zone currently enterable? */
    fun mayEnter(zoneName: String): Boolean {
        val z = zones.byName(zoneName) ?: return true
        return !z.isPrivate || overrideFor(z) != null
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

    /**
     * Temporarily lift the restriction for the single zone [zoneName] only, e.g. after a person on the robot's screen
     * allowed it to cross that area. Same rules as [grantOverride]: hard expiry (max [MAX_OVERRIDE_MILLIS]), audited.
     */
    fun grantZoneOverride(
        zoneName: String,
        reason: OverrideReason,
        durationMillis: Long,
        grantedBy: String
    ): Override {
        require(durationMillis > 0) { "Override must have a positive duration" }
        require(durationMillis <= MAX_OVERRIDE_MILLIS) {
            "Override capped at ${MAX_OVERRIDE_MILLIS / 60000} minutes; re-grant if still needed"
        }
        val o = Override(reason, grantedBy, clock(), clock() + durationMillis, zoneName)
        zoneOverrides[zoneName.lowercase()] = o
        record(AuditEntry.OverrideGranted(o.grantedAt, reason, grantedBy, o.expiresAt, zoneName))
        return o
    }

    fun revokeZoneOverride(zoneName: String, revokedBy: String) {
        val o = zoneOverrides.remove(zoneName.lowercase()) ?: return
        record(AuditEntry.OverrideRevoked(clock(), o.reason, revokedBy, zoneName))
    }

    /** Active per-zone overrides by zone name; expired ones are removed (and audited) on the way. */
    fun activeZoneOverrides(): Map<String, Override> {
        val now = clock()
        zoneOverrides.entries.removeAll { (_, o) ->
            (now >= o.expiresAt).also { expired -> if (expired) record(AuditEntry.OverrideExpired(o.expiresAt, o.reason, o.zoneName)) }
        }
        return zoneOverrides.values.associateBy { it.zoneName ?: "" }
    }

    /** The override that currently lets the robot into [zone]: a global one, or one for this zone; null if none. */
    private fun overrideFor(zone: Zone): Override? {
        activeOverride()?.let { return it }
        val o = zoneOverrides[zone.name.lowercase()] ?: return null
        if (clock() >= o.expiresAt) {
            zoneOverrides.remove(zone.name.lowercase())
            record(AuditEntry.OverrideExpired(o.expiresAt, o.reason, o.zoneName))
            return null
        }
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
    val expiresAt: Long,
    /** The single zone this override applies to; null = all zones. */
    val zoneName: String? = null
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
        val expiresAt: Long,
        val zoneName: String? = null
    ) : AuditEntry

    data class OverrideRevoked(
        override val timestamp: Long,
        val reason: OverrideReason,
        val revokedBy: String,
        val zoneName: String? = null
    ) : AuditEntry

    data class OverrideExpired(
        override val timestamp: Long,
        val reason: OverrideReason,
        val zoneName: String? = null
    ) : AuditEntry

    data class OverriddenEntry(
        override val timestamp: Long,
        val zoneName: String,
        val reason: OverrideReason
    ) : AuditEntry
}
