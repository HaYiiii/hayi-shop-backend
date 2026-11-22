package vn.hayi.shop.identity.web.backoffice;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.identity.application.OtpService;
import vn.hayi.shop.identity.application.StaffProvisioningService;
import vn.hayi.shop.identity.application.StaffQueryService;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.security.RequiresStepUp;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.spec.backoffice.api.StaffApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffAssignmentDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffBranchRoleDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffBranchRoleInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffCreatedDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StaffDto;
import vn.hayi.shop.staff.api.StaffDirectory;

/** Staff provisioning and reads (API 10 §1, Identity §2.11, Decision AQ); no screen until S12. */
@RestController
class BackofficeStaffController implements StaffApi {

	private static final String STAFF = "/api/backoffice/v1/staff/";

	private final StaffProvisioningService provisioning;

	private final StaffQueryService queries;

	private final HttpServletRequest request;

	BackofficeStaffController(StaffProvisioningService provisioning, StaffQueryService queries,
			HttpServletRequest request) {
		this.provisioning = provisioning;
		this.queries = queries;
		this.request = request;
	}

	@Override
	@RequiresPermission(Permission.STAFF_MANAGE)
	@RequiresStepUp
	public ResponseEntity<StaffCreatedDto> createStaff(StaffCreateRequestDto body, String xCsrfToken) {
		List<InvalidFieldsException.Violation> violations = new ArrayList<>();
		String phone = null;
		if (body.getLoginPhone() != null) {
			phone = OtpService.normalizePhone(body.getLoginPhone()).orElse(null);
			if (phone == null) {
				violations.add(new InvalidFieldsException.Violation("loginPhone", "PHONE_INVALID"));
			}
		}
		if (body.getLoginPhone() == null && body.getLoginEmail() == null) {
			violations.add(new InvalidFieldsException.Violation("loginPhone", "REQUIRED"));
		}
		var roles = body.getBranchRoles();
		if (new HashSet<>(roles.stream().map(role -> role.getBranchId() + "|" + role.getRoleCode()).toList())
			.size() != roles.size()) {
			violations.add(new InvalidFieldsException.Violation("branchRoles", "DUPLICATE"));
		}
		if (!violations.isEmpty()) {
			throw new InvalidFieldsException(violations);
		}
		StaffProvisioningService.Provisioned created = this.provisioning.provision(
				new StaffProvisioningService.NewStaff(body.getEmployeeCode().strip().toUpperCase(Locale.ROOT),
						body.getFullName().strip(), phone, body.getLoginEmail(), body.getContactPhone(),
						body.getJoinedOn(),
						body.getAssignments()
							.stream()
							.map(assignment -> new StaffDirectory.NewAssignment(assignment.getBranchId(),
									assignment.getAssignmentType().getValue(), assignment.getEffectiveFrom()))
							.toList(),
						roles.stream().map(BackofficeStaffController::toRole).toList(),
						body.getCredential().getMode().getValue()),
				AuthenticatedSession.require().userId(), ClientInfo.from(this.request));
		return ResponseEntity.created(URI.create(STAFF + created.staffId()))
			.body(new StaffCreatedDto(created.staffId(), created.userId(), created.linkedExistingUser(),
					created.temporaryPassword(), created.mfaEnrollmentRequired()));
	}

	@Override
	@RequiresPermission(Permission.STAFF_VIEW)
	public ResponseEntity<StaffDto> getStaff(UUID staffId) {
		StaffQueryService.StaffDetail detail = this.queries.get(staffId, AuthenticatedSession.require().userId());
		StaffDirectory.Staff staff = detail.staff();
		return ResponseEntity.ok(new StaffDto(staff.staffId(), staff.userId(), staff.employeeCode(), staff.fullName(),
				staff.contactPhone(), StaffDto.StatusEnum.fromValue(staff.status()), staff.joinedOn(),
				staff.assignments()
					.stream()
					.map(assignment -> new StaffAssignmentDto(assignment.assignmentId(), assignment.branchId(),
							StaffAssignmentDto.AssignmentTypeEnum.fromValue(assignment.assignmentType()),
							StaffAssignmentDto.StatusEnum.fromValue(assignment.status()), assignment.effectiveFrom(),
							assignment.effectiveTo()))
					.toList(),
				detail.branchRoles()
					.stream()
					.map(role -> new StaffBranchRoleDto(role.branchId(), role.roleCode()))
					.toList(),
				staff.version()));
	}

	private static StaffProvisioningService.BranchRole toRole(StaffBranchRoleInputDto role) {
		return new StaffProvisioningService.BranchRole(role.getBranchId(), role.getRoleCode());
	}

}
