package vn.hayi.shop.staff.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.staff.api.StaffProfiles;

@Component
class JdbcStaffProfiles implements StaffProfiles {

	private final JdbcClient jdbc;

	JdbcStaffProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<String> fullName(UUID userId) {
		return this.jdbc.sql("SELECT full_name FROM stf_staff_profile WHERE user_id = ?")
			.param(toBytes(userId))
			.query(String.class)
			.optional();
	}

}
