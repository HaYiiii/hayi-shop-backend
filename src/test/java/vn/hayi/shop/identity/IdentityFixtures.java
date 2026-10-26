package vn.hayi.shop.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.identity.infrastructure.MfaSecretCipher;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.time.BranchTime;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * Creates Backoffice users straight in the {@code iam_*} tables, so login/session tests do not depend on a
 * provisioning flow that does not exist yet. Users get role ADMIN (STORE scope), leaving OWNER to the bootstrap, and
 * an active TOTP factor with {@link #TOTP_SECRET} unless created without MFA. Branch staff get a StaffProfile, an
 * assignment and a BRANCH role; their sessions are opened directly, since staff login arrives with staff provisioning.
 */
@TestComponent
class IdentityFixtures {

	static final String PASSWORD = "correct horse battery staple";

	/** Base32 TOTP secret of every fixture user with MFA, as their authenticator app holds it. */
	static final String TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	private final JdbcClient jdbc;

	private final PasswordEncoder passwordEncoder;

	private final MfaSecretCipher cipher;

	private final Tokens tokens;

	private final CsrfTokens csrfTokens;

	private final Clock clock;

	IdentityFixtures(JdbcClient jdbc, PasswordEncoder passwordEncoder, MfaSecretCipher cipher, Tokens tokens,
			CsrfTokens csrfTokens, Clock clock) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.cipher = cipher;
		this.tokens = tokens;
		this.csrfTokens = csrfTokens;
		this.clock = clock;
	}

	/** Today at the Branch by the application clock, the date the assignment check compares against. */
	LocalDate today() {
		return BranchTime.localDate(this.clock.instant(), BranchTime.DEFAULT_ZONE);
	}

	/** A unique email, so tests sharing the database never see each other's users. */
	static String uniqueEmail() {
		return "admin-" + UUID.randomUUID() + "@hayi.test";
	}

	UUID storeAdmin(String email) {
		return storeAdmin(email, PASSWORD, false);
	}

	UUID storeAdmin(String email, String password, boolean mustChangePassword) {
		UUID userId = storeAdminWithoutMfa(email, password, mustChangePassword);
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO iam_mfa_factor (id, user_id, factor_type, secret_encrypted, status, activated_at, created_at, updated_at)
				VALUES (?, ?, 'TOTP', ?, 'ACTIVE', ?, ?, ?)
				""").params(id(UuidV7.generate()), id(userId),
					this.cipher.encrypt(userId, BackofficeAuth.base32Decode(TOTP_SECRET)), now, now, now).update();
		return userId;
	}

	/** An ADMIN who has not enrolled MFA yet, e.g. right after provisioning. */
	UUID storeAdminWithoutMfa(String email) {
		return storeAdminWithoutMfa(email, PASSWORD, false);
	}

	private UUID storeAdminWithoutMfa(String email, String password, boolean mustChangePassword) {
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

	/** A staff member with a StaffProfile, no STORE role and no Branch yet: see {@link #assignToBranch}. */
	UUID branchStaff() {
		UUID userId = userWithoutRole("staff-" + UUID.randomUUID() + "@hayi.test", PASSWORD, false);
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO stf_staff_profile (id, user_id, employee_code, full_name, status, created_at, updated_at)
				VALUES (?, ?, ?, 'Nhân viên test', 'ACTIVE', ?, ?)
				""").params(id(UuidV7.generate()), id(userId), "E" + UUID.randomUUID().toString().substring(0, 8), now, now)
			.update();
		return userId;
	}

	/** An active SUPPORTING assignment since yesterday plus {@code role} at that Branch. */
	void assignToBranch(UUID userId, UUID branchId, String role) {
		addAssignment(userId, branchId, "ACTIVE", today().minusDays(1), null);
		grantBranchRole(userId, branchId, role);
	}

	void addAssignment(UUID userId, UUID branchId, String status, LocalDate from, LocalDate to) {
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO stf_staff_branch_assignment (id, staff_id, branch_id, assignment_type, status, effective_from,
				                                         effective_to, created_at, updated_at)
				SELECT ?, id, ?, 'SUPPORTING', ?, ?, ?, ?, ? FROM stf_staff_profile WHERE user_id = ?
				""").params(id(UuidV7.generate()), id(branchId), status, from, to, now, now, id(userId)).update();
	}

	void endAssignment(UUID userId, UUID branchId) {
		this.jdbc.sql("""
				UPDATE stf_staff_branch_assignment SET status = 'ENDED', effective_to = ?
				WHERE branch_id = ? AND staff_id = (SELECT id FROM stf_staff_profile WHERE user_id = ?)
				""").params(today(), id(branchId), id(userId)).update();
	}

	/** Staff offboarding step one (Staff §16.12): the profile goes INACTIVE, assignments and roles left as they were. */
	void deactivateStaff(UUID userId) {
		this.jdbc.sql("UPDATE stf_staff_profile SET status = 'INACTIVE', updated_at = ? WHERE user_id = ?")
			.params(Instant.now(), id(userId)).update();
	}

	void grantBranchRole(UUID userId, UUID branchId, String role) {
		this.jdbc.sql("""
				INSERT INTO iam_user_branch_role (id, user_id, branch_id, role_id, granted_at)
				SELECT ?, ?, ?, id, ? FROM iam_role WHERE code = ?
				""").params(id(UuidV7.generate()), id(userId), id(branchId), Instant.now(), role).update();
	}

	void revokeBranchRole(UUID userId, UUID branchId, String role) {
		this.jdbc.sql("""
				DELETE FROM iam_user_branch_role
				WHERE user_id = ? AND branch_id = ? AND role_id = (SELECT id FROM iam_role WHERE code = ?)
				""").params(id(userId), id(branchId), role).update();
	}

	void grantStoreRole(UUID userId, String role) {
		this.jdbc.sql("""
				INSERT INTO iam_user_store_role (id, user_id, role_id, granted_at)
				SELECT ?, ?, id, ? FROM iam_role WHERE code = ?
				""").params(id(UuidV7.generate()), id(userId), Instant.now(), role).update();
	}

	void revokeStoreRole(UUID userId, String role) {
		this.jdbc.sql("""
				DELETE FROM iam_user_store_role WHERE user_id = ? AND role_id = (SELECT id FROM iam_role WHERE code = ?)
				""").params(id(userId), role).update();
	}

	/** A live Backoffice session with its access and CSRF cookies, as a completed login would leave them. */
	Session openSession(UUID userId) {
		UUID sessionId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO iam_user_session (id, user_id, persona, channel, created_at, last_active_at, expires_at)
				VALUES (?, ?, 'STAFF', 'BACKOFFICE_WEB', ?, ?, ?)
				""").params(id(sessionId), id(userId), now, now, now.plusSeconds(3600)).update();
		return new Session(this.tokens.issueAccessToken(Surface.BACKOFFICE, userId, sessionId), null,
				this.csrfTokens.issue(sessionId));
	}

	/** Turns the user's MFA off, like a Manager who never enabled it (Decision D4). */
	void disableMfa(UUID userId) {
		this.jdbc.sql("UPDATE iam_mfa_factor SET status = 'DISABLED' WHERE user_id = ?").param(id(userId)).update();
	}

	/** Revokes the user's sessions straight in the database, as another instance would. */
	void revokeSessionsElsewhere(UUID userId) {
		this.jdbc.sql("UPDATE iam_user_session SET revoked_at = ?, revoke_reason = 'LOGOUT' WHERE user_id = ?")
			.params(Instant.now(), id(userId)).update();
	}

	long securityEvents(String type, UUID userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = ? AND user_id = ?")
			.params(type, id(userId)).query(Long.class).single();
	}

	private static byte[] id(UUID uuid) {
		return UuidBinary.toBytes(uuid);
	}

}
