package vn.hayi.shop.identity.web.backoffice;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.identity.application.RoleAssignmentService;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.security.RequiresStepUp;
import vn.hayi.shop.platform.web.spec.backoffice.api.RolesApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.BranchRoleSetDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.RoleCodesRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.RoleListDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.RoleSummaryDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.UserRolesDto;

/** Role catalog and grants (API 10 §2, API 01-security §7.7, Decision AR). */
@RestController
class BackofficeRoleController implements RolesApi {

	private final RoleAssignmentService roles;

	private final HttpServletRequest request;

	BackofficeRoleController(RoleAssignmentService roles, HttpServletRequest request) {
		this.roles = roles;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.STAFF_VIEW)
	public ResponseEntity<RoleListDto> listRoles() {
		return ResponseEntity.ok(new RoleListDto(this.roles.catalog()
			.stream()
			.map(role -> new RoleSummaryDto(role.code(), role.name(), RoleSummaryDto.ScopeEnum.fromValue(role.scope()),
					role.requiresMfa(), role.permissions()))
			.toList()));
	}

	@Override
	@RequiresPermission(Permission.STAFF_VIEW)
	public ResponseEntity<UserRolesDto> getUserRoles(UUID userId) {
		return ResponseEntity.ok(toDto(this.roles.rolesOf(userId)));
	}

	@Override
	@RequiresPermission(Permission.ROLE_ASSIGN)
	@RequiresStepUp
	public ResponseEntity<UserRolesDto> replaceStoreRoles(UUID userId, RoleCodesRequestDto body, String xCsrfToken) {
		return ResponseEntity.ok(toDto(this.roles.replaceStoreRoles(userId, body.getRoleCodes(), caller(), client())));
	}

	@Override
	@RequiresPermission(Permission.ROLE_ASSIGN)
	@RequiresStepUp
	public ResponseEntity<UserRolesDto> replaceBranchRoles(UUID userId, UUID branchId, RoleCodesRequestDto body,
			String xCsrfToken) {
		return ResponseEntity
			.ok(toDto(this.roles.replaceBranchRoles(userId, branchId, body.getRoleCodes(), caller(), client())));
	}

	private static UserRolesDto toDto(RoleAssignmentService.UserRoles roles) {
		return new UserRolesDto(roles.userId(), roles.storeRoles(), roles.branchRoles()
			.entrySet()
			.stream()
			.map(entry -> new BranchRoleSetDto(entry.getKey(), entry.getValue()))
			.toList());
	}

	private static UUID caller() {
		return AuthenticatedSession.require().userId();
	}

	private ClientInfo client() {
		return ClientInfo.from(this.request);
	}

}
