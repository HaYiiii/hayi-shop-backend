package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.platform.security.EffectiveAccess;
import vn.hayi.shop.platform.security.EffectiveAccess.BranchAccess;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.PermissionResolver;
import vn.hayi.shop.staff.api.StaffAssignments;

/**
 * Effective permission (Identity §2.7): permissions of the STORE roles, plus at Branch X those of the BRANCH roles at X
 * when the user has an active staff assignment at X (Decision A). Read from the database on every call, no cache
 * (Decision M3).
 */
@Component
class EffectivePermissions implements PermissionResolver {

	private final JdbcClient jdbc;

	private final StaffAssignments assignments;

	EffectivePermissions(JdbcClient jdbc, StaffAssignments assignments) {
		this.jdbc = jdbc;
		this.assignments = assignments;
	}

	@Override
	public EffectiveAccess resolve(UUID userId) {
		Set<String> storeRoles = new HashSet<>();
		Set<Permission> storePermissions = EnumSet.noneOf(Permission.class);
		this.jdbc.sql("""
				SELECT r.code, p.code FROM iam_user_store_role usr
				JOIN iam_role r ON r.id = usr.role_id
				LEFT JOIN iam_role_permission rp ON rp.role_id = r.id
				LEFT JOIN iam_permission p ON p.id = rp.permission_id
				WHERE usr.user_id = ? AND r.allowed_scope = 'STORE'
				""").param(toBytes(userId)).query(rs -> {
			storeRoles.add(rs.getString(1));
			permission(rs.getString(2)).ifPresent(storePermissions::add);
		});
		Set<UUID> assigned = this.assignments.activeBranchIds(userId);
		Map<UUID, Set<String>> branchRoles = new HashMap<>();
		Map<UUID, Set<Permission>> branchPermissions = new HashMap<>();
		if (!assigned.isEmpty()) {
			this.jdbc.sql("""
					SELECT ubr.branch_id, r.code, p.code FROM iam_user_branch_role ubr
					JOIN iam_role r ON r.id = ubr.role_id
					LEFT JOIN iam_role_permission rp ON rp.role_id = r.id
					LEFT JOIN iam_permission p ON p.id = rp.permission_id
					WHERE ubr.user_id = ? AND r.allowed_scope = 'BRANCH'
					""").param(toBytes(userId)).query(rs -> {
				UUID branchId = UuidBinary.fromBytes(rs.getBytes(1));
				if (assigned.contains(branchId)) {
					branchRoles.computeIfAbsent(branchId, id -> new HashSet<>()).add(rs.getString(2));
					permission(rs.getString(3)).ifPresent(
							branchPermissions.computeIfAbsent(branchId, id -> EnumSet.noneOf(Permission.class))::add);
				}
			});
		}
		Map<UUID, BranchAccess> branches = new HashMap<>();
		branchRoles.forEach((branchId, roles) -> branches.put(branchId,
				new BranchAccess(roles, branchPermissions.getOrDefault(branchId, Set.of()))));
		return new EffectiveAccess(userId, storeRoles, storePermissions, branches);
	}

	/** Codes outside the catalog in code (none today) grant nothing. */
	private static Optional<Permission> permission(String code) {
		return Arrays.stream(Permission.values()).filter(permission -> permission.name().equals(code)).findFirst();
	}

}
