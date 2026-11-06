package vn.hayi.shop.catalog;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.hayi.shop.identity.IdentityFixtures;
import vn.hayi.shop.platform.persistence.UuidBinary;

/** Test data shared by the Catalog Backoffice tests. */
final class CatalogFixtures {

	private CatalogFixtures() {
	}

	/**
	 * A STORE user with {@code CATALOG_VIEW} and {@code CATALOG_MANAGE} but not {@code CATALOG_PRICE_MANAGE}. No seed
	 * role has that combination (API 01-security §7.1), so a test-only role isolates the price permission (Decision
	 * Z4, AB4): an Admin's ADMIN role is swapped for it.
	 */
	static UUID catalogManagerWithoutPricePermission(JdbcClient jdbc, IdentityFixtures fixtures) {
		jdbc.sql("""
				INSERT IGNORE INTO iam_role (id, code, name, allowed_scope, is_system, requires_mfa, created_at, updated_at)
				VALUES (?, 'TEST_CATALOG_NO_PRICE', 'Test: catalog without prices', 'STORE', 0, 1, NOW(6), NOW(6))
				""").param(UuidBinary.toBytes(UUID.randomUUID())).update();
		jdbc.sql("""
				INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
				SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code IN ('CATALOG_VIEW', 'CATALOG_MANAGE')
				WHERE r.code = 'TEST_CATALOG_NO_PRICE'
				""").update();
		UUID userId = fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		jdbc.sql("DELETE FROM iam_user_store_role WHERE user_id = ?").param(UuidBinary.toBytes(userId)).update();
		jdbc.sql("""
				INSERT INTO iam_user_store_role (id, user_id, role_id, granted_at)
				SELECT ?, ?, id, NOW(6) FROM iam_role WHERE code = 'TEST_CATALOG_NO_PRICE'
				""").param(UuidBinary.toBytes(UUID.randomUUID())).param(UuidBinary.toBytes(userId)).update();
		return userId;
	}

}
