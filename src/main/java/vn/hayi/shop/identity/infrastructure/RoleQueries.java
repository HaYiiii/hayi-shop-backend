package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

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

	/** A system role by code (Decision D3). */
	public Optional<Role> findRole(String code) {
		return this.jdbc.sql("SELECT id, code, allowed_scope, requires_mfa FROM iam_role WHERE code = ?")
			.param(code)
			.query((rs, n) -> new Role(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("code"),
					rs.getString("allowed_scope"), rs.getBoolean("requires_mfa")))
			.optional();
	}

	public record Role(UUID id, String code, String allowedScope, boolean requiresMfa) {
	}

	/** Grants {@code roleId} at {@code branchId}; the caller checked the role is BRANCH-scoped (Decision A). */
	public void grantBranchRole(UUID userId, UUID branchId, UUID roleId, UUID grantedBy, Instant now) {
		this.jdbc.sql("""
				INSERT INTO iam_user_branch_role (id, user_id, branch_id, role_id, granted_at, granted_by_user_id)
				VALUES (?, ?, ?, ?, ?, ?)
				""").params(toBytes(UuidV7.generate()), toBytes(userId), toBytes(branchId), toBytes(roleId), now,
				toBytes(grantedBy)).update();
	}

	/** The user's BRANCH roles, by Branch then role code. */
	public List<BranchRole> branchRoles(UUID userId) {
		return this.jdbc.sql("""
				SELECT ubr.branch_id, r.code FROM iam_user_branch_role ubr JOIN iam_role r ON r.id = ubr.role_id
				WHERE ubr.user_id = ? ORDER BY ubr.branch_id, r.code
				""").param(toBytes(userId))
			.query((rs, n) -> new BranchRole(UuidBinary.fromBytes(rs.getBytes(1)), rs.getString(2)))
			.list();
	}

	public record BranchRole(UUID branchId, String roleCode) {
	}

	/** Roles with {@code requires_mfa} (every STORE role, Decision D4) make MFA mandatory at Backoffice login. */
	public boolean requiresMfa(UUID userId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM iam_user_store_role usr JOIN iam_role r ON r.id = usr.role_id
				               WHERE usr.user_id = ? AND r.requires_mfa = 1)
				""").param(toBytes(userId)).query(Boolean.class).single();
	}

}
