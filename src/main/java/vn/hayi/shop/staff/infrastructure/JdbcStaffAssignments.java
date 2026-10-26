package vn.hayi.shop.staff.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.time.BranchTime;
import vn.hayi.shop.staff.api.StaffAssignments;

@Component
class JdbcStaffAssignments implements StaffAssignments {

	private final JdbcClient jdbc;

	private final Clock clock;

	JdbcStaffAssignments(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Set<UUID> activeBranchIds(UUID userId) {
		LocalDate today = BranchTime.localDate(Instant.now(this.clock), BranchTime.DEFAULT_ZONE);
		return this.jdbc.sql("""
				SELECT a.branch_id FROM stf_staff_branch_assignment a JOIN stf_staff_profile s ON s.id = a.staff_id
				WHERE s.user_id = ? AND s.status = 'ACTIVE' AND a.status = 'ACTIVE' AND a.effective_from <= ?
				  AND (a.effective_to IS NULL OR a.effective_to >= ?)
				""").params(toBytes(userId), today, today)
			.query((rs, n) -> UuidBinary.fromBytes(rs.getBytes(1)))
			.set();
	}

}
