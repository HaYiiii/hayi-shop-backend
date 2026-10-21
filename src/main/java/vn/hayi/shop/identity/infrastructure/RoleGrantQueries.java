package vn.hayi.shop.identity.infrastructure;

import static vn.hayi.shop.platform.persistence.UuidBinary.toBytes;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.hayi.shop.platform.persistence.UuidBinary;

/** The user's role grants with their permissions, read live (Decision M3); scope rules are applied by the caller. */
@Component
public class RoleGrantQueries {

	private final JdbcClient jdbc;

	RoleGrantQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** One row per STORE role × permission; {@code permission} is null for a role without permissions. */
	public List<RoleGrant> storeGrants(UUID userId) {
		return this.jdbc.sql("""
				SELECT r.code, p.code FROM iam_user_store_role usr
				JOIN iam_role r ON r.id = usr.role_id
				LEFT JOIN iam_role_permission rp ON rp.role_id = r.id
				LEFT JOIN iam_permission p ON p.id = rp.permission_id
				WHERE usr.user_id = ? AND r.allowed_scope = 'STORE'
				""").param(toBytes(userId))
			.query((rs, n) -> new RoleGrant(null, rs.getString(1), rs.getString(2)))
			.list();
	}

	/** One row per Branch × BRANCH role × permission, whether or not the user is assigned there. */
	public List<RoleGrant> branchGrants(UUID userId) {
		return this.jdbc.sql("""
				SELECT ubr.branch_id, r.code, p.code FROM iam_user_branch_role ubr
				JOIN iam_role r ON r.id = ubr.role_id
				LEFT JOIN iam_role_permission rp ON rp.role_id = r.id
				LEFT JOIN iam_permission p ON p.id = rp.permission_id
				WHERE ubr.user_id = ? AND r.allowed_scope = 'BRANCH'
				""").param(toBytes(userId))
			.query((rs, n) -> new RoleGrant(UuidBinary.fromBytes(rs.getBytes(1)), rs.getString(2), rs.getString(3)))
			.list();
	}

	/** @param branchId null for a STORE role */
	public record RoleGrant(UUID branchId, String role, String permission) {
	}

}
