package vn.hayi.shop.identity.application;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.hayi.shop.platform.security.AccessDecision;
import vn.hayi.shop.platform.security.Permission;

/**
 * What a Backoffice user may do (Identity §2.7, Decision A, M2): STORE roles apply at every Branch; BRANCH roles only
 * at the Branches listed, i.e. those with an active staff assignment. Role codes stay strings: roles are rows of
 * {@code iam_role} (custom roles later), while permissions are the fixed catalog in code.
 *
 * @param storeRoles role codes at STORE scope
 * @param storePermissions permissions of the STORE roles
 * @param branches per Branch, the BRANCH roles in effect there and their permissions (without the STORE ones)
 */
public record EffectiveAccess(UUID userId, Set<String> storeRoles, Set<Permission> storePermissions,
		Map<UUID, BranchAccess> branches) {

	public EffectiveAccess {
		storeRoles = Set.copyOf(storeRoles);
		storePermissions = Set.copyOf(storePermissions);
		branches = Map.copyOf(branches);
	}

	/**
	 * Permission first, then branch scope, then the permission at that Branch (Decision M1). Without a Branch (a
	 * cross-Branch view) holding the permission in some scope is enough.
	 */
	public AccessDecision decide(Permission permission, Optional<UUID> branchId) {
		if (!holdsAnywhere(permission)) {
			return AccessDecision.PERMISSION_DENIED;
		}
		if (branchId.isEmpty()) {
			return AccessDecision.GRANTED;
		}
		if (!inScope(branchId.get())) {
			return AccessDecision.BRANCH_SCOPE_DENIED;
		}
		return permissionsAt(branchId.get()).contains(permission) ? AccessDecision.GRANTED
				: AccessDecision.PERMISSION_DENIED;
	}

	/** Held at STORE scope or at any Branch. */
	boolean holdsAnywhere(Permission permission) {
		return this.storePermissions.contains(permission)
				|| this.branches.values().stream().anyMatch(branch -> branch.permissions().contains(permission));
	}

	/** A STORE role sees every Branch; otherwise only the Branches with a BRANCH role in effect. */
	boolean inScope(UUID branchId) {
		return !this.storeRoles.isEmpty() || this.branches.containsKey(branchId);
	}

	/** Every permission at {@code branchId}, STORE permissions included (what {@code /auth/me} lists, Decision M4). */
	public Set<Permission> permissionsAt(UUID branchId) {
		Set<Permission> permissions = EnumSet.noneOf(Permission.class);
		permissions.addAll(this.storePermissions);
		BranchAccess branch = this.branches.get(branchId);
		if (branch != null) {
			permissions.addAll(branch.permissions());
		}
		return permissions;
	}

	public record BranchAccess(Set<String> roles, Set<Permission> permissions) {

		public BranchAccess {
			roles = Set.copyOf(roles);
			permissions = Set.copyOf(permissions);
		}

	}

}
