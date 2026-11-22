package vn.hayi.shop.identity.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.PermissionScope;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.staff.api.StaffDirectory;
import vn.hayi.shop.staff.api.StaffErrorCode;

/**
 * {@code GET /staff/{staffId}} (Decision AQ5): the StaffProfile with its assignments and Branch roles. A caller with
 * {@code STAFF_VIEW} at some Branches only sees staff with an {@code ACTIVE} assignment at one of them.
 */
@Service
public class StaffQueryService {

	private final StaffDirectory staff;

	private final RoleQueries roles;

	private final AccessService access;

	StaffQueryService(StaffDirectory staff, RoleQueries roles, AccessService access) {
		this.staff = staff;
		this.roles = roles;
		this.access = access;
	}

	public record StaffDetail(StaffDirectory.Staff staff, List<StaffProvisioningService.BranchRole> branchRoles) {
	}

	/** @throws DomainException {@code STAFF_NOT_FOUND} when unknown or outside the caller's Branches */
	@Transactional(readOnly = true)
	public StaffDetail get(UUID staffId, UUID callerUserId) {
		StaffDirectory.Staff found = this.staff.find(staffId).orElseThrow(() -> notFound(staffId));
		PermissionScope scope = this.access.scopeOf(callerUserId, Permission.STAFF_VIEW);
		boolean visible = scope.everyBranch() || found.assignments()
			.stream()
			.anyMatch(assignment -> "ACTIVE".equals(assignment.status())
					&& scope.branchIds().contains(assignment.branchId()));
		if (!visible) {
			throw notFound(staffId);
		}
		return new StaffDetail(found, this.roles.branchRoles(found.userId())
			.stream()
			.map(role -> new StaffProvisioningService.BranchRole(role.branchId(), role.roleCode()))
			.toList());
	}

	private static DomainException notFound(UUID staffId) {
		return new DomainException(StaffErrorCode.STAFF_NOT_FOUND, "No staff " + staffId);
	}

}
