package vn.hayi.shop.platform.security;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a Backoffice user may do, resolved server-side (Identity §2.7, Decision A, M2): STORE roles apply at every
 * Branch; BRANCH roles only at the Branches listed, i.e. those with an active staff assignment.
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

	/** Held at STORE scope or at any Branch. */
	public boolean holdsAnywhere(Permission permission) {
		return this.storePermissions.contains(permission)
				|| this.branches.values().stream().anyMatch(branch -> branch.permissions().contains(permission));
	}

	/** A STORE role sees every Branch; otherwise only the Branches with a BRANCH role in effect. */
	public boolean inScope(UUID branchId) {
		return !this.storeRoles.isEmpty() || this.branches.containsKey(branchId);
	}

	/** Every permission at {@code branchId}, STORE permissions included. */
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
