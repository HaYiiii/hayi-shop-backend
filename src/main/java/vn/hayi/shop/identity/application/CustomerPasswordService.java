package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.PasswordPolicy;
import vn.hayi.shop.identity.domain.RevokeReason;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.domain.VerificationChallenge;
import vn.hayi.shop.identity.domain.VerificationChallengeRepository;
import vn.hayi.shop.identity.domain.VerificationPurpose;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ThrottledException;
import vn.hayi.shop.staff.api.StaffProfiles;

/**
 * Customer password reset by OTP and password change (Identity §2.3–2.4, API 01-security §6.1, Decision AS). The
 * credential is shared by the User's personas (Identity §2.2), so a reset ends every session of the User and a
 * change every other one; a User who is also Staff keeps the internal policy (AS4).
 */
@Service
public class CustomerPasswordService {

	private final Tokens tokens;

	private final VerificationChallengeRepository challenges;

	private final LoginIdentifierRepository identifiers;

	private final UserRepository users;

	private final UserCredentialRepository credentials;

	private final UserSessionRepository sessions;

	private final SessionService sessionService;

	private final LoginService login;

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	private final RoleQueries roles;

	private final PasswordEncoder passwordEncoder;

	private final SecurityEventLog securityEvents;

	private final TransactionTemplate transactions;

	private final Clock clock;

	CustomerPasswordService(Tokens tokens, VerificationChallengeRepository challenges,
			LoginIdentifierRepository identifiers, UserRepository users, UserCredentialRepository credentials,
			UserSessionRepository sessions, SessionService sessionService, LoginService login,
			CustomerProfiles customerProfiles, StaffProfiles staffProfiles, RoleQueries roles,
			PasswordEncoder passwordEncoder, SecurityEventLog securityEvents, TransactionTemplate transactions,
			Clock clock) {
		this.tokens = tokens;
		this.challenges = challenges;
		this.identifiers = identifiers;
		this.users = users;
		this.credentials = credentials;
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.login = login;
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
		this.roles = roles;
		this.passwordEncoder = passwordEncoder;
		this.securityEvents = securityEvents;
		this.transactions = transactions;
		this.clock = clock;
	}

	/**
	 * Sets a new password with a {@code PASSWORD_RECOVERY} proof and ends every session of the User (AS2).
	 * @throws DomainException {@code VERIFICATION_TOKEN_INVALID}, {@code AUTH_PASSWORD_POLICY_VIOLATION}
	 */
	public void reset(String verificationToken, String newPassword, ClientInfo client) {
		this.transactions.executeWithoutResult(status -> {
			Instant now = this.clock.instant();
			VerificationChallenge proof = this.tokens.verifyVerificationToken(verificationToken)
				.flatMap(this.challenges::findByIdForUpdate)
				.filter(challenge -> challenge.getPurpose() == VerificationPurpose.PASSWORD_RECOVERY)
				.filter(VerificationChallenge::isVerified)
				.orElseThrow(CustomerPasswordService::invalidToken);
			UUID userId = this.identifiers.findByTypeAndNormalizedValue(IdentifierType.PHONE, proof.getTarget())
				.map(LoginIdentifier::getUserId)
				.filter(this.customerProfiles::existsForUser)
				.orElseThrow(CustomerPasswordService::invalidToken);
			User user = this.users.findByIdForUpdate(userId).orElseThrow();
			UserCredential credential = this.credentials.findByIdForUpdate(userId).orElseThrow(CustomerPasswordService::invalidToken);
			checkPolicy(userId, newPassword);
			credential.changePassword(this.passwordEncoder.encode(newPassword), now);
			user.clearLoginLock(now);
			proof.consumeVerified(now);
			this.sessions.findByUserIdAndRevokedAtIsNull(userId)
				.forEach(session -> this.sessionService.revoke(session, RevokeReason.PASSWORD_CHANGED, now, client));
			this.securityEvents.record(SecurityEventType.PASSWORD_RESET, userId, null, client);
		});
	}

	/**
	 * Changes the signed-in Customer's password and ends their other sessions (AS3). A wrong current password counts
	 * toward the login lock, so it is committed even though the request fails.
	 * @throws DomainException {@code AUTH_INVALID_CREDENTIALS}, {@code AUTH_PASSWORD_POLICY_VIOLATION}
	 * @throws ThrottledException {@code AUTH_LOGIN_LOCKED}
	 */
	public void change(UUID userId, UUID sessionId, String currentPassword, String newPassword, ClientInfo client) {
		LoginResult refused = this.transactions.execute(status -> {
			Instant now = this.clock.instant();
			User user = this.users.findByIdForUpdate(userId).orElseThrow();
			if (user.isLocked(now)) {
				return new LoginResult.Locked(IdentityErrorCode.AUTH_LOGIN_LOCKED, user.lockRemaining(now));
			}
			UserCredential credential = this.credentials.findByIdForUpdate(userId).orElse(null);
			if (credential == null || !this.passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
				return this.login.registerFailure(user, now, client);
			}
			checkPolicy(userId, newPassword);
			if (this.passwordEncoder.matches(newPassword, credential.getPasswordHash())) {
				throw PasswordPolicy.violation();
			}
			credential.changePassword(this.passwordEncoder.encode(newPassword), now);
			this.sessions.findByUserIdAndRevokedAtIsNull(userId)
				.stream()
				.filter(session -> !session.getId().equals(sessionId))
				.forEach(session -> this.sessionService.revoke(session, RevokeReason.PASSWORD_CHANGED, now, client));
			this.securityEvents.record(SecurityEventType.PASSWORD_CHANGED, userId, sessionId, client);
			return null;
		});
		switch (refused) {
			case null -> {
			}
			case LoginResult.Locked locked -> throw new ThrottledException(locked.code(), "Login locked",
					locked.retryAfter(), Math.max(1, (locked.retryAfter().toSeconds() + 59) / 60));
			case LoginResult.Rejected rejected -> throw new DomainException(rejected.code(), "Wrong current password");
			default -> throw new IllegalStateException("Unexpected " + refused);
		}
	}

	/** A User who is also Staff keeps the internal policy; a Customer only gets the Customer one (AS4). */
	private void checkPolicy(UUID userId, String newPassword) {
		boolean staff = this.staffProfiles.fullName(userId).isPresent() || this.roles.hasAnyStoreRole(userId)
				|| !this.roles.branchRoles(userId).isEmpty();
		if (staff) {
			PasswordPolicy.check(newPassword);
		}
		else {
			PasswordPolicy.checkCustomer(newPassword);
		}
	}

	private static DomainException invalidToken() {
		return new DomainException(IdentityErrorCode.VERIFICATION_TOKEN_INVALID,
				"Invalid, expired or used verification token");
	}

}
