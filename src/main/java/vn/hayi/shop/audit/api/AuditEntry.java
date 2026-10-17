package vn.hayi.shop.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import vn.hayi.shop.shared.actor.ActorContext;

/**
 * One business audit record (Audit §17.3): who did what, on which entity, in which Branch, before/after and why.
 * {@code oldValue}/{@code newValue} are purpose-specific diffs, already redacted: never passwords, OTPs, tokens or
 * secrets (Audit §17.6). The trace id is taken from the current request by the recorder.
 */
public record AuditEntry(ActorContext actor, AuditSource source, String action, String entityType, UUID entityId,
		UUID branchId, Map<String, ?> oldValue, Map<String, ?> newValue, String reason, String ipAddress,
		String userAgent) {

	public AuditEntry {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(entityId, "entityId");
		oldValue = (oldValue != null) ? Map.copyOf(oldValue) : null;
		newValue = (newValue != null) ? Map.copyOf(newValue) : null;
	}

	public static AuditEntry of(ActorContext actor, AuditSource source, String action, String entityType,
			UUID entityId) {
		return new AuditEntry(actor, source, action, entityType, entityId, null, null, null, null, null, null);
	}

	public AuditEntry withBranch(UUID branchId) {
		return new AuditEntry(this.actor, this.source, this.action, this.entityType, this.entityId, branchId,
				this.oldValue, this.newValue, this.reason, this.ipAddress, this.userAgent);
	}

	public AuditEntry withOldValue(Map<String, ?> oldValue) {
		return new AuditEntry(this.actor, this.source, this.action, this.entityType, this.entityId, this.branchId,
				oldValue, this.newValue, this.reason, this.ipAddress, this.userAgent);
	}

	public AuditEntry withNewValue(Map<String, ?> newValue) {
		return new AuditEntry(this.actor, this.source, this.action, this.entityType, this.entityId, this.branchId,
				this.oldValue, newValue, this.reason, this.ipAddress, this.userAgent);
	}

	public AuditEntry withReason(String reason) {
		return new AuditEntry(this.actor, this.source, this.action, this.entityType, this.entityId, this.branchId,
				this.oldValue, this.newValue, reason, this.ipAddress, this.userAgent);
	}

	public AuditEntry withClient(String ipAddress, String userAgent) {
		return new AuditEntry(this.actor, this.source, this.action, this.entityType, this.entityId, this.branchId,
				this.oldValue, this.newValue, this.reason, ipAddress, userAgent);
	}

}
