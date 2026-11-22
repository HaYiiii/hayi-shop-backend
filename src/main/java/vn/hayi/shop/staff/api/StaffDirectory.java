package vn.hayi.shop.staff.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes and reads StaffProfiles and their Branch assignments (Staff Operations §16.10–16.11) for Identity's staff
 * provisioning (Identity §2.11, Decision AQ1). Staff Operations stays the owner of this data and of
 * {@code employeeCode}.
 */
public interface StaffDirectory {

	/** @param employeeCode as stored: trimmed and upper-cased */
	record NewStaff(UUID userId, String employeeCode, String fullName, String contactPhone, LocalDate joinedOn,
			List<NewAssignment> assignments) {
	}

	/** @param assignmentType {@code PRIMARY} or {@code SUPPORTING} */
	record NewAssignment(UUID branchId, String assignmentType, LocalDate effectiveFrom) {
	}

	record Staff(UUID staffId, UUID userId, String employeeCode, String fullName, String contactPhone, String status,
			LocalDate joinedOn, long version, List<Assignment> assignments) {
	}

	record Assignment(UUID assignmentId, UUID branchId, String assignmentType, String status, LocalDate effectiveFrom,
			LocalDate effectiveTo) {
	}

	boolean existsForUser(UUID userId);

	/**
	 * Creates an {@code ACTIVE} StaffProfile with {@code ACTIVE} assignments, inside the caller's transaction.
	 * @return the new {@code staffId}
	 * @throws vn.hayi.shop.shared.error.DomainException {@code STAFF_EMPLOYEE_CODE_TAKEN},
	 * {@code STAFF_ALREADY_EXISTS_FOR_USER}, {@code ASSIGNMENT_PRIMARY_CONFLICT}, {@code ASSIGNMENT_DUPLICATE_ACTIVE}
	 */
	UUID create(NewStaff staff);

	Optional<Staff> find(UUID staffId);

}
