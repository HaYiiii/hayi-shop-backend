package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.branch.api.BranchDirectory;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.RevokeReason;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.actor.ActorContext;
import vn.hayi.shop.shared.actor.Persona;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.staff.api.StaffAssignments;

/**
 * Grants and revokes roles (API 10 §2, API 01-security §7.7, Decision A, D4, AR). The escalation rules are checked
 * before the transaction, so their {@code PERMISSION_ESCALATION_ATTEMPT} event is kept when the request is refused.
 * A change takes effect on the next request, since permissions are resolved per request (M3).
 */
@Service
public class RoleAssignmentService {

	static final Set<String> PROTECTED = Set.of("OWNER", "ADMIN");

	private final RoleQueries roles;

	private final UserRepository users;

	private final UserSessionRepository sessions;

	private final SessionService sessionService;

	private final StaffAssignments assignments;

	private final BranchDirectory branches;

	private final SecurityEventLog securityEvents;

	private final AuditRecorder audit;

	private final TransactionTemplate transactions;

	private final Clock clock;

	RoleAssignmentService(RoleQueries roles, UserRepository users, UserSessionRepository sessions,
			SessionService sessionService, StaffAssignments assignments, BranchDirectory branches,
			SecurityEventLog securityEvents, AuditRecorder audit, TransactionTemplate transactions, Clock clock) {
		this.roles = roles;
		this.users = users;
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.assignments = assignments;
		this.branches = branches;
		this.securityEvents = securityEvents;
		this.audit = audit;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** The roles of a user (AR1); {@code branchRoles} by Branch, codes sorted. */
	public record UserRoles(UUID userId, List<String> storeRoles, Map<UUID, List<String>> branchRoles) {
	}

	public record RoleSummary(String code, String name, String scope, boolean requiresMfa, List<String> permissions) {
	}

	public List<RoleSummary> catalog() {
		return this.roles.allRoles()
			.stream()
			.map(role -> new RoleSummary(role.code(), role.name(), role.scope(), role.requiresMfa(), role.permissions()))
			.toList();
	}

	/** @throws DomainException {@code USER_NOT_FOUND} */
	public UserRoles rolesOf(UUID userId) {
		requireUser(userId);
		return read(userId);
	}

	/** Replaces the user's STORE roles (AR1–AR4). */
	public UserRoles replaceStoreRoles(UUID userId, List<String> roleCodes, UUID callerId, ClientInfo client) {
		requireUser(userId);
		Map<String, RoleQueries.Role> wanted = resolve(roleCodes, "STORE");
		Set<String> current = new TreeSet<>(this.roles.storeRoleCodes(userId));
		guard(userId, callerId, minus(wanted.keySet(), current), minus(current, wanted.keySet()), client, null);
		this.transactions.executeWithoutResult(status -> {
			Instant now = this.clock.instant();
			// Serialises role changes of one user, then diffs again: a double-submitted PUT is a no-op (AR1).
			this.users.findByIdForUpdate(userId).orElseThrow();
			Set<String> locked = new TreeSet<>(this.roles.storeRoleCodes(userId));
			Set<String> added = minus(wanted.keySet(), locked);
			Set<String> removed = minus(locked, wanted.keySet());
			guard(userId, callerId, added, removed, client, null);
			if (removed.contains("OWNER")) {
				UUID owner = this.roles.lockRole("OWNER");
				if (this.roles.countHolders(owner) <= 1) {
					throw new DomainException(IdentityErrorCode.ROLE_LAST_OWNER, "The last OWNER cannot be revoked");
				}
			}
			for (String code : removed) {
				this.roles.revokeStoreRole(userId, this.roles.findRole(code).orElseThrow().id());
				record(callerId, client, "ROLE_REVOKE", userId, Map.of("role", code, "scope", "STORE"));
			}
			for (String code : added) {
				this.roles.grantStoreRole(userId, wanted.get(code).id(), callerId, now);
				record(callerId, client, "ROLE_GRANT", userId, Map.of("role", code, "scope", "STORE"));
			}
			revokeStaffSessionsIfNoAccessLeft(userId, now, client);
		});
		return read(userId);
	}

	/** Replaces the user's roles at one Branch; empty = revoke them all there (AR1–AR4). */
	public UserRoles replaceBranchRoles(UUID userId, UUID branchId, List<String> roleCodes, UUID callerId,
			ClientInfo client) {
		requireUser(userId);
		this.branches.requireExists(branchId);
		Map<String, RoleQueries.Role> wanted = resolve(roleCodes, "BRANCH");
		Set<String> current = currentAt(userId, branchId);
		guard(userId, callerId, minus(wanted.keySet(), current), minus(current, wanted.keySet()), client, branchId);
		if (!minus(wanted.keySet(), current).isEmpty() && !this.assignments.activeBranchIds(userId).contains(branchId)) {
			throw new DomainException(IdentityErrorCode.ROLE_REQUIRES_ASSIGNMENT,
					"No ACTIVE assignment at Branch " + branchId);
		}
		this.transactions.executeWithoutResult(status -> {
			Instant now = this.clock.instant();
			this.users.findByIdForUpdate(userId).orElseThrow();
			Set<String> locked = currentAt(userId, branchId);
			Set<String> added = minus(wanted.keySet(), locked);
			Set<String> removed = minus(locked, wanted.keySet());
			for (String code : removed) {
				this.roles.revokeBranchRole(userId, branchId, this.roles.findRole(code).orElseThrow().id());
				record(callerId, client, "ROLE_REVOKE", userId,
						Map.of("role", code, "scope", "BRANCH", "branchId", branchId));
			}
			for (String code : added) {
				this.roles.grantBranchRole(userId, branchId, wanted.get(code).id(), callerId, now);
				record(callerId, client, "ROLE_GRANT", userId,
						Map.of("role", code, "scope", "BRANCH", "branchId", branchId));
			}
			revokeStaffSessionsIfNoAccessLeft(userId, now, client);
		});
		return read(userId);
	}

	/**
	 * Unknown code → {@code 400}; a role of another scope → {@code ROLE_SCOPE_MISMATCH} (AR2).
	 */
	private Map<String, RoleQueries.Role> resolve(List<String> roleCodes, String scope) {
		Map<String, RoleQueries.Role> resolved = new LinkedHashMap<>();
		for (String code : new LinkedHashSet<>(roleCodes)) {
			RoleQueries.Role role = this.roles.findRole(code)
				.orElseThrow(() -> new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unknown role " + code));
			if (!scope.equals(role.allowedScope())) {
				throw new DomainException(IdentityErrorCode.ROLE_SCOPE_MISMATCH,
						"Role " + code + " is not a " + scope + " role");
			}
			resolved.put(code, role);
		}
		return resolved;
	}

	/**
	 * Self-assignment, and OWNER/ADMIN changes by a non-OWNER, are refused and logged as escalation attempts (§7.7).
	 */
	private void guard(UUID userId, UUID callerId, Set<String> added, Set<String> removed, ClientInfo client,
			UUID branchId) {
		if (added.isEmpty() && removed.isEmpty()) {
			return;
		}
		IdentityErrorCode refusal = null;
		if (userId.equals(callerId)) {
			refusal = IdentityErrorCode.ROLE_SELF_ASSIGN_FORBIDDEN;
		}
		else if (!this.roles.storeRoleCodes(callerId).contains("OWNER")
				&& (added.stream().anyMatch(PROTECTED::contains) || removed.stream().anyMatch(PROTECTED::contains))) {
			refusal = IdentityErrorCode.ROLE_ASSIGN_FORBIDDEN_ROLE;
		}
		if (refusal != null) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("rule", refusal.name());
			metadata.put("targetUserId", userId.toString());
			metadata.put("added", List.copyOf(added));
			metadata.put("removed", List.copyOf(removed));
			if (branchId != null) {
				metadata.put("branchId", branchId.toString());
			}
			this.securityEvents.record(SecurityEventType.PERMISSION_ESCALATION_ATTEMPT, callerId, null, null, client,
					metadata);
			throw new DomainException(refusal, "Role change refused: " + refusal);
		}
	}

	private Set<String> currentAt(UUID userId, UUID branchId) {
		Set<String> current = new TreeSet<>();
		this.roles.branchRoles(userId)
			.stream()
			.filter(role -> role.branchId().equals(branchId))
			.forEach(role -> current.add(role.roleCode()));
		return current;
	}

	/** No STORE role and no BRANCH role left → the user's Backoffice sessions end (AR4); Customer ones stay. */
	private void revokeStaffSessionsIfNoAccessLeft(UUID userId, Instant now, ClientInfo client) {
		if (!this.roles.storeRoleCodes(userId).isEmpty() || !this.roles.branchRoles(userId).isEmpty()) {
			return;
		}
		this.sessions.findByUserIdAndPersonaAndRevokedAtIsNull(userId, Persona.STAFF)
			.forEach(session -> this.sessionService.revoke(session, RevokeReason.ADMIN, now, client));
	}

	private UserRoles read(UUID userId) {
		Map<UUID, List<String>> branchRoles = new TreeMap<>();
		this.roles.branchRoles(userId)
			.forEach(role -> branchRoles.computeIfAbsent(role.branchId(), id -> new java.util.ArrayList<>())
				.add(role.roleCode()));
		return new UserRoles(userId, this.roles.storeRoleCodes(userId), branchRoles);
	}

	private void requireUser(UUID userId) {
		if (this.users.findById(userId).isEmpty()) {
			throw new DomainException(IdentityErrorCode.USER_NOT_FOUND, "No user " + userId);
		}
	}

	private static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> result = new TreeSet<>(a);
		result.removeAll(b);
		return result;
	}

	private void record(UUID callerId, ClientInfo client, String action, UUID userId, Map<String, ?> value) {
		this.audit.record(AuditEntry.of(ActorContext.staff(callerId, null), AuditSource.BACKOFFICE_WEB, action, "USER",
				userId)
			.withNewValue(value)
			.withClient(client.ipAddress(), client.userAgent()));
	}

}
