package vn.hayi.shop.identity.application;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.application.EffectiveAccess.BranchAccess;
import vn.hayi.shop.identity.infrastructure.RoleGrantQueries;
import vn.hayi.shop.identity.infrastructure.RoleGrantQueries.RoleGrant;
import vn.hayi.shop.platform.security.AccessDecision;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.PermissionEvaluator;
import vn.hayi.shop.staff.api.StaffAssignments;

/**
 * Effective permission (Identity §2.7): permissions of the STORE roles, plus at Branch X those of the BRANCH roles at X
 * when the user has an active staff assignment at X (Decision A). Resolved from the database on every call, no cache
 * (Decision M3), so a role or assignment change applies to the next request. The role and assignment reads share one
 * read-only transaction, so a change landing between them cannot mix two states; this relies on InnoDB's default
 * {@code REPEATABLE READ}, where the snapshot is taken at the first read ({@code READ COMMITTED} would not hold it). On the class, not on
 * {@link #resolve}: {@link #evaluate} calls it through {@code this}, past the proxy.
 */
@Service
@Transactional(readOnly = true)
public class AccessService implements PermissionEvaluator {

	private static final Map<String, Permission> BY_CODE = Arrays.stream(Permission.values())
		.collect(Collectors.toUnmodifiableMap(Permission::name, Function.identity()));

	private final RoleGrantQueries grants;

	private final StaffAssignments assignments;

	AccessService(RoleGrantQueries grants, StaffAssignments assignments) {
		this.grants = grants;
		this.assignments = assignments;
	}

	@Override
	public AccessDecision evaluate(UUID userId, Permission permission, Optional<UUID> branchId) {
		return resolve(userId).decide(permission, branchId);
	}

	public EffectiveAccess resolve(UUID userId) {
		Set<String> storeRoles = new HashSet<>();
		Set<Permission> storePermissions = EnumSet.noneOf(Permission.class);
		for (RoleGrant grant : this.grants.storeGrants(userId)) {
			storeRoles.add(grant.role());
			permission(grant).ifPresent(storePermissions::add);
		}
		Map<UUID, Set<String>> branchRoles = new HashMap<>();
		Map<UUID, Set<Permission>> branchPermissions = new HashMap<>();
		Set<UUID> assigned = this.assignments.activeBranchIds(userId);
		if (!assigned.isEmpty()) {
			for (RoleGrant grant : this.grants.branchGrants(userId)) {
				if (assigned.contains(grant.branchId())) {
					branchRoles.computeIfAbsent(grant.branchId(), id -> new HashSet<>()).add(grant.role());
					permission(grant).ifPresent(branchPermissions
						.computeIfAbsent(grant.branchId(), id -> EnumSet.noneOf(Permission.class))::add);
				}
			}
		}
		Map<UUID, BranchAccess> branches = new HashMap<>();
		branchRoles.forEach((branchId, roles) -> branches.put(branchId,
				new BranchAccess(roles, branchPermissions.getOrDefault(branchId, Set.of()))));
		return new EffectiveAccess(userId, storeRoles, storePermissions, branches);
	}

	/** Codes outside the catalog in code (none today) grant nothing. */
	private static Optional<Permission> permission(RoleGrant grant) {
		return Optional.ofNullable(grant.permission()).map(BY_CODE::get);
	}

}
