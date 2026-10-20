package com.example.robocontrol.movement

/**
 * Decides whether the robot may go to or be at a position. Private zones are refused unless a time-boxed override is
 * active; every override is recorded in the audit log. Areas marked forbidden in the robot's map tool
 * ([PoseStatus.FORBIDDEN]) always stop the robot.
 * @param zones zones of the active map
 * @param margin metres added around private zones
 * @param clock time source in milliseconds
 */
class PrivacyGuard(
    zones: MapZones,
    /** Metres added around private zones. */
    var margin: Double = DEFAULT_MARGIN,
    private val clock: () -> Long = System::currentTimeMillis
) {

    var zones: MapZones = zones
        private set

    private var override: Override? = null

    /** Per-zone overrides, keyed by lower-case zone name: lift the restriction for ONE area only. */
    private val zoneOverrides = mutableMapOf<String, Override>()
    private val auditLog = mutableListOf<AuditEntry>()

    /** Private zones the robot was inside at the last [violationAt]; entries are audited only when one is added. */
    private val insideZones = mutableSetOf<String>()

    /** Called for every new audit entry (MapNavigation writes it to the navigation log, so it can be seen). */
    var onAudit: ((AuditEntry) -> Unit)? = null

    /** Replaces the zones the guard decides on, e.g. after a map change or after an area was drawn. */
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
     * Checks the robot's current pose.
     * @param pose current pose
     * @return the violation, or null if the position is allowed
     */
    fun violationAt(pose: RobotPose): Violation? {
        if (pose.status == PoseStatus.FORBIDDEN) {
            return Violation.SdkForbiddenArea
        }
        val blocked = zones.zonesAt(pose.point, margin).filter { it.isPrivate && overrideFor(it) == null }
        // Audit only the moment of entering: while the robot stands inside an area this is called every 500 ms, and
        // one entry per call pushed every grant and revocation out of the log within minutes.
        val names = blocked.map { it.name }.toSet()
        for (name in names - insideZones) record(AuditEntry.EnteredPrivateZone(clock(), name, pose.point))
        insideZones.retainAll(names)
        insideZones.addAll(names)
        val zone = blocked.firstOrNull() ?: return null
        return Violation.PrivateZone(zone)
    }

    /** Convenience: is this named zone currently enterable? */
    fun mayEnter(zoneName: String): Boolean {
        val z = zones.byName(zoneName) ?: return true
        return !z.isPrivate || overrideFor(z) != null
    }

    // ---- override -------------------------------------------------------

    /**
     * Lifts the restrictions of all private zones for a limited time.
     * @param reason why the override is granted
     * @param durationMillis duration, at most [MAX_OVERRIDE_MILLIS]
     * @param grantedBy who granted it
     * @return the new override
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
     * Lifts the restriction of one zone for a limited time.
     * @param zoneName name of the zone
     * @param reason why the override is granted
     * @param durationMillis duration, at most [MAX_OVERRIDE_MILLIS]
     * @param grantedBy who granted it
     * @return the new override
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

    /** Ends every per-zone permission, e.g. when the map changes (area names repeat across maps: "Bereich 1"). */
    fun revokeAllZoneOverrides(revokedBy: String) {
        zoneOverrides.keys.toList().forEach { key -> zoneOverrides[key]?.zoneName?.let { revokeZoneOverride(it, revokedBy) } }
    }

    /** Ends the permission for one zone early and writes that to the audit log. */
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

    /**
     * Ends the global override early and writes that to the audit log.
     * @param revokedBy who ended it, for the audit log.
     */
    fun revokeOverride(revokedBy: String) {
        val o = override ?: return
        override = null
        record(AuditEntry.OverrideRevoked(clock(), o.reason, revokedBy))
    }

    /** Whether a global override (all zones lifted) is active right now. */
    fun overrideActive(): Boolean = activeOverride() != null

    /** The active global override, or null. An expired override is removed and audited here. */
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

    /** Empties the audit log. */
    fun clearAudit() = auditLog.clear()

    /** Adds [e] to the audit log (bounded to [MAX_AUDIT_ENTRIES]) and passes it to [onAudit]. */
    private fun record(e: AuditEntry) {
        auditLog.add(e)
        if (auditLog.size > MAX_AUDIT_ENTRIES) auditLog.removeAt(0)
        runCatching { onAudit?.invoke(e) }
    }

    companion object {
        const val DEFAULT_MARGIN = 0.5
        const val MAX_OVERRIDE_MILLIS = 60L * 60L * 1000L // 1 hour
        const val MAX_AUDIT_ENTRIES = 500
    }
}

/** The target is outside every private zone. */
sealed interface Decision {
    /** The target lies inside [zone], but an active override allows it. */
    data object Allowed : Decision
    data class AllowedByOverride(val zone: Zone, val override: Override) : Decision
    /** The target lies inside [zone]; the drive must not start. */
    data class Refused(val zone: Zone) : Decision

    val isAllowed: Boolean get() = this !is Refused
}

/** Why the robot has to stop while it drives. */
sealed interface Violation {
    /** The robot entered the private [zone] (including its margin). */
    data class PrivateZone(val zone: Zone) : Violation
    /** Area marked forbidden in OrionStar's map tool. Not overridable from here. */
    data object SdkForbiddenArea : Violation
}

/** Why a privacy restriction was lifted. Stored with every override and in the audit log. */
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

/**
 * A time-boxed permission to enter private zones.
 * @property reason why it was granted
 * @property grantedBy who granted it
 * @property grantedAt start time in milliseconds
 * @property expiresAt end time in milliseconds
 */
data class Override(
    val reason: OverrideReason,
    val grantedBy: String,
    val grantedAt: Long,
    val expiresAt: Long,
    /** The single zone this override applies to; null = all zones. */
    val zoneName: String? = null
)

/** One entry of the privacy audit log. Positions are stored for the decision, but [describe] never shows them. */
sealed interface AuditEntry {
    val timestamp: Long

    /** One line for the screen, the phone and the log: the decision only, no coordinates (they would be a movement trace). */
    fun describe(): String = when (this) {
        is Refused -> "refused: target in \"$zoneName\""
        is EnteredPrivateZone -> "entered \"$zoneName\""
        is OverrideGranted -> "permission granted${zoneName?.let { " for \"$it\"" } ?: " for all areas"} by $grantedBy ($reason) until " +
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(expiresAt))
        is OverrideRevoked -> "permission${zoneName?.let { " for \"$it\"" } ?: ""} revoked by $revokedBy"
        is OverrideExpired -> "permission${zoneName?.let { " for \"$it\"" } ?: ""} expired"
        is OverriddenEntry -> "entered \"$zoneName\" with permission ($reason)"
    }

    /** A drive was refused because its target lies in a private zone. */
    data class Refused(
        override val timestamp: Long,
        val zoneName: String,
        val target: Point2D
    ) : AuditEntry

    /** The robot entered a private zone while driving and was stopped. */
    data class EnteredPrivateZone(
        override val timestamp: Long,
        val zoneName: String,
        val at: Point2D
    ) : AuditEntry

    /** A person granted an override (for one zone or for all). */
    data class OverrideGranted(
        override val timestamp: Long,
        val reason: OverrideReason,
        val grantedBy: String,
        val expiresAt: Long,
        val zoneName: String? = null
    ) : AuditEntry

    /** An override was ended before it expired. */
    data class OverrideRevoked(
        override val timestamp: Long,
        val reason: OverrideReason,
        val revokedBy: String,
        val zoneName: String? = null
    ) : AuditEntry

    /** An override ran out. */
    data class OverrideExpired(
        override val timestamp: Long,
        val reason: OverrideReason,
        val zoneName: String? = null
    ) : AuditEntry

    /** The robot entered a zone that was allowed by an override at that moment. */
    data class OverriddenEntry(
        override val timestamp: Long,
        val zoneName: String,
        val reason: OverrideReason
    ) : AuditEntry
}
