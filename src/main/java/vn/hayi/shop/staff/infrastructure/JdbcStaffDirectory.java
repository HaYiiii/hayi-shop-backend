package vn.hayi.shop.staff.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;
import static vn.hayi.shop.platform.persistence.UuidBinary.fromBytes;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;
import vn.hayi.shop.staff.api.StaffDirectory;
import vn.hayi.shop.staff.api.StaffErrorCode;

/**
 * {@link StaffDirectory} over {@code stf_staff_profile} / {@code stf_staff_branch_assignment} (ERD 04). The unique
 * keys back the checks, so a concurrent request still ends in the same error codes.
 */
@Component
class JdbcStaffDirectory implements StaffDirectory {

	private final JdbcClient jdbc;

	private final Clock clock;

	JdbcStaffDirectory(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
	public boolean existsForUser(UUID userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM stf_staff_profile WHERE user_id = ?")
			.param(toBytes(userId))
			.query(Integer.class)
			.single() > 0;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID create(NewStaff staff) {
		requireValidAssignments(staff.assignments());
		if (existsForUser(staff.userId())) {
			throw alreadyExists(staff.userId());
		}
		if (this.jdbc.sql("SELECT COUNT(*) FROM stf_staff_profile WHERE employee_code = ?")
			.param(staff.employeeCode())
			.query(Integer.class)
			.single() > 0) {
			throw codeTaken(staff.employeeCode());
		}
		UUID staffId = UuidV7.generate();
		Instant now = this.clock.instant();
		try {
			this.jdbc.sql("""
					INSERT INTO stf_staff_profile (id, user_id, employee_code, full_name, contact_phone, status, joined_on,
					  created_at, updated_at, version)
					VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
					""")
				.params(toBytes(staffId), toBytes(staff.userId()), staff.employeeCode(), staff.fullName(),
						staff.contactPhone(), (staff.joinedOn() != null) ? Date.valueOf(staff.joinedOn()) : null, now, now)
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null && message.contains("uk_stf_staff_profile__employee_code")) {
				throw codeTaken(staff.employeeCode());
			}
			if (message != null && message.contains("uk_stf_staff_profile__user_id")) {
				throw alreadyExists(staff.userId());
			}
			throw ex;
		}
		for (NewAssignment assignment : staff.assignments()) {
			this.jdbc.sql("""
					INSERT INTO stf_staff_branch_assignment (id, staff_id, branch_id, assignment_type, status,
					  effective_from, effective_to, created_at, updated_at)
					VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL, ?, ?)
					""")
				.params(toBytes(UuidV7.generate()), toBytes(staffId), toBytes(assignment.branchId()),
						assignment.assignmentType(), Date.valueOf(assignment.effectiveFrom()), now, now)
				.update();
		}
		return staffId;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Staff> find(UUID staffId) {
		List<Assignment> assignments = this.jdbc.sql("""
				SELECT id, branch_id, assignment_type, status, effective_from, effective_to
				FROM stf_staff_branch_assignment WHERE staff_id = ? ORDER BY effective_from, id
				""")
			.param(toBytes(staffId))
			.query((rs, n) -> new Assignment(fromBytes(rs.getBytes("id")), fromBytes(rs.getBytes("branch_id")),
					rs.getString("assignment_type"), rs.getString("status"),
					rs.getDate("effective_from").toLocalDate(),
					(rs.getDate("effective_to") != null) ? rs.getDate("effective_to").toLocalDate() : null))
			.list();
		return this.jdbc.sql("""
				SELECT id, user_id, employee_code, full_name, contact_phone, status, joined_on, version
				FROM stf_staff_profile WHERE id = ?
				""")
			.param(toBytes(staffId))
			.query((rs, n) -> new Staff(fromBytes(rs.getBytes("id")), fromBytes(rs.getBytes("user_id")),
					rs.getString("employee_code"), rs.getString("full_name"), rs.getString("contact_phone"),
					rs.getString("status"),
					(rs.getDate("joined_on") != null) ? rs.getDate("joined_on").toLocalDate() : null,
					rs.getLong("version"), assignments))
			.optional();
	}

	/** At most one {@code PRIMARY} and one assignment per Branch (ERD 04 unique markers, Decision AQ2). */
	private static void requireValidAssignments(List<NewAssignment> assignments) {
		if (assignments.stream().filter(assignment -> "PRIMARY".equals(assignment.assignmentType())).count() > 1) {
			throw new DomainException(StaffErrorCode.ASSIGNMENT_PRIMARY_CONFLICT, "More than one PRIMARY assignment");
		}
		Set<UUID> branches = new HashSet<>();
		for (NewAssignment assignment : assignments) {
			if (!branches.add(assignment.branchId())) {
				throw new DomainException(StaffErrorCode.ASSIGNMENT_DUPLICATE_ACTIVE,
						"Two assignments at Branch " + assignment.branchId());
			}
		}
	}

	private static DomainException codeTaken(String employeeCode) {
		return new DomainException(StaffErrorCode.STAFF_EMPLOYEE_CODE_TAKEN, "Employee code taken: " + employeeCode);
	}

	private static DomainException alreadyExists(UUID userId) {
		return new DomainException(StaffErrorCode.STAFF_ALREADY_EXISTS_FOR_USER, "User " + userId + " is already staff");
	}

}
