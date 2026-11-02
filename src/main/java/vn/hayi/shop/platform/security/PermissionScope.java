package vn.hayi.shop.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * Where a user holds a permission, for a cross-Branch view that filters its own data (Decision M1): every Branch
 * when a STORE role grants it (Decision M2), else only the listed Branches.
 */
public record PermissionScope(boolean everyBranch, Set<UUID> branchIds) {

	public PermissionScope {
		branchIds = Set.copyOf(branchIds);
	}

	public static PermissionScope all() {
		return new PermissionScope(true, Set.of());
	}

	public static PermissionScope only(Set<UUID> branchIds) {
		return new PermissionScope(false, branchIds);
	}

}
