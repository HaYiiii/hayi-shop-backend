package vn.hayi.shop.identity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Creates Backoffice users straight in the {@code iam_*} tables, so login/session tests do not depend on a
 * provisioning flow that does not exist yet. Users get role ADMIN (STORE scope), leaving OWNER to the bootstrap.
 */
@TestComponent
class IdentityFixtures {

	static final String PASSWORD = "correct horse battery staple";

	private final JdbcClient jdbc;

	private final PasswordEncoder passwordEncoder;

	IdentityFixtures(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
	}

	/** A unique email, so tests sharing the database never see each other's users. */
	static String uniqueEmail() {
		return "admin-" + UUID.randomUUID() + "@hayi.test";
	}

	UUID storeAdmin(String email) {
		return storeAdmin(email, PASSWORD, false);
	}

	UUID storeAdmin(String email, String password, boolean mustChangePassword) {
		UUID userId = userWithoutRole(email, password, mustChangePassword);
		this.jdbc.sql("""
				INSERT INTO iam_user_store_role (id, user_id, role_id, granted_at)
				SELECT ?, ?, id, ? FROM iam_role WHERE code = 'ADMIN'
				""").params(id(UuidV7.generate()), id(userId), Instant.now()).update();
		return userId;
	}

	/** A user with a password but no STORE role, e.g. a customer account: no Backoffice access. */
	UUID withoutBackofficeAccess(String email) {
		return userWithoutRole(email, PASSWORD, false);
	}

	private UUID userWithoutRole(String email, String password, boolean mustChangePassword) {
		UUID userId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(id(userId), now, now).update();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at, updated_at)
				VALUES (?, ?, 'EMAIL', ?, ?, ?, ?)
				""").params(id(UuidV7.generate()), id(userId), email.toLowerCase(Locale.ROOT), now, now, now).update();
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, ?, ?, ?)
				""").params(id(userId), this.passwordEncoder.encode(password), mustChangePassword, now, now).update();
		return userId;
	}

	long securityEvents(String type, UUID userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = ? AND user_id = ?")
			.params(type, id(userId)).query(Long.class).single();
	}

	private static byte[] id(UUID uuid) {
		return UuidBinary.toBytes(uuid);
	}

}
