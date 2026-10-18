package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;

/** Reads over the seeded system roles (Decision D3). */
@Component
public class RoleQueries {

	private final JdbcClient jdbc;

	RoleQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The role's id, with its row locked until the transaction ends: concurrent grants of the same role (two
	 * instances bootstrapping the Owner) run one after the other.
	 */
	public UUID lockRole(String code) {
		return this.jdbc.sql("SELECT id FROM iam_role WHERE code = ? FOR UPDATE")
			.param(code)
			.query((rs, n) -> UuidBinary.fromBytes(rs.getBytes(1)))
			.single();
	}

	public boolean anyUserHoldsStoreRole(UUID roleId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM iam_user_store_role WHERE role_id = ?)")
			.param(toBytes(roleId)).query(Boolean.class).single();
	}

	/** STORE-scope roles open the Backoffice without a StaffProfile (Identity §2.5). */
	public boolean hasAnyStoreRole(UUID userId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM iam_user_store_role usr JOIN iam_role r ON r.id = usr.role_id
				               WHERE usr.user_id = ? AND r.allowed_scope = 'STORE')
				""").param(toBytes(userId)).query(Boolean.class).single();
	}

}
