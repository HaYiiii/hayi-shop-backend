package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/** Every system role with its permission codes, by scope then code (Decision AR1). */
	public List<RoleWithPermissions> allRoles() {
		var rows = this.jdbc.sql("""
				SELECT r.code, r.name, r.allowed_scope, r.requires_mfa, p.code AS permission
				FROM iam_role r LEFT JOIN iam_role_permission rp ON rp.role_id = r.id
				LEFT JOIN iam_permission p ON p.id = rp.permission_id
				ORDER BY r.allowed_scope DESC, r.code, p.code
				""").query().listOfRows();
		Map<String, RoleWithPermissions> byCode = new LinkedHashMap<>();
		for (var row : rows) {
			RoleWithPermissions role = byCode.computeIfAbsent((String) row.get("code"),
					code -> new RoleWithPermissions(code, (String) row.get("name"), (String) row.get("allowed_scope"),
							toBoolean(row.get("requires_mfa")), new ArrayList<>()));
			if (row.get("permission") != null) {
				role.permissions().add((String) row.get("permission"));
			}
		}
		return List.copyOf(byCode.values());
	}

	private static boolean toBoolean(Object value) {
		return (value instanceof Boolean flag) ? flag : (value instanceof Number number) && number.intValue() != 0;
	}

	public record RoleWithPermissions(String code, String name, String scope, boolean requiresMfa,
			List<String> permissions) {
	}

	/** The user's STORE role codes, sorted. */
	public List<String> storeRoleCodes(UUID userId) {
		return this.jdbc.sql("""
				SELECT r.code FROM iam_user_store_role usr JOIN iam_role r ON r.id = usr.role_id
				WHERE usr.user_id = ? ORDER BY r.code
				""").param(toBytes(userId)).query(String.class).list();
	}

	public void grantStoreRole(UUID userId, UUID roleId, UUID grantedBy, Instant now) {
		this.jdbc.sql("""
				INSERT INTO iam_user_store_role (id, user_id, role_id, granted_at, granted_by_user_id)
				VALUES (?, ?, ?, ?, ?)
				""").params(toBytes(UuidV7.generate()), toBytes(userId), toBytes(roleId), now, toBytes(grantedBy))
			.update();
	}

	public void revokeStoreRole(UUID userId, UUID roleId) {
		this.jdbc.sql("DELETE FROM iam_user_store_role WHERE user_id = ? AND role_id = ?")
			.params(toBytes(userId), toBytes(roleId)).update();
	}

	public void revokeBranchRole(UUID userId, UUID branchId, UUID roleId) {
		this.jdbc.sql("DELETE FROM iam_user_branch_role WHERE user_id = ? AND branch_id = ? AND role_id = ?")
			.params(toBytes(userId), toBytes(branchId), toBytes(roleId)).update();
	}

	/** Users holding OWNER, read with a lock on the role row first (Decision AR3). */
	public int countHolders(UUID roleId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM iam_user_store_role WHERE role_id = ? FOR UPDATE")
			.param(toBytes(roleId)).query(Integer.class).single();
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
