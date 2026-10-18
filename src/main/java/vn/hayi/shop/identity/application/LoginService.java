package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.application.IdentityProperties.Mfa;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.LoginLockPolicy;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.VerificationChallenge;
import vn.hayi.shop.identity.domain.VerificationChallengeRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.SecurityProperties;
import vn.hayi.shop.platform.security.Tokens;

/**
 * Backoffice login with email + password (Identity §2.2, §2.4–2.5, API 01-security §6.2–6.3, Decision K2–K3, L).
 * <p>
 * Unknown identifier and wrong password look the same to the caller. After the password, exactly one step may come
 * first, in this order: a temporary password is replaced (K3); an active MFA factor is answered (L1); a role that
 * requires MFA enrolls one (D4, L6). Only then is a session opened.
 */
@Service
public class LoginService {

	private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

	private final LoginIdentifierRepository identifiers;

	private final UserRepository users;

	private final UserCredentialRepository credentials;

	private final VerificationChallengeRepository challenges;

	private final MfaCodes mfaCodes;

	private final RoleQueries roles;

	private final SessionIssuer sessionIssuer;

	private final Tokens tokens;

	private final PasswordEncoder passwordEncoder;

	private final SecurityEventLog securityEvents;

	private final IdentityProperties properties;

	private final SecurityProperties securityProperties;

	private final Clock clock;

	/** Compared against when the user does not exist, so both failures take the same time. */
	private final String unknownUserHash;

	LoginService(LoginIdentifierRepository identifiers, UserRepository users, UserCredentialRepository credentials,
			VerificationChallengeRepository challenges, MfaCodes mfaCodes, RoleQueries roles, SessionIssuer sessionIssuer,
			Tokens tokens, PasswordEncoder passwordEncoder, SecurityEventLog securityEvents, IdentityProperties properties,
			SecurityProperties securityProperties, Clock clock) {
		this.identifiers = identifiers;
		this.users = users;
		this.credentials = credentials;
		this.challenges = challenges;
		this.mfaCodes = mfaCodes;
		this.roles = roles;
		this.sessionIssuer = sessionIssuer;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.securityEvents = securityEvents;
		this.properties = properties;
		this.securityProperties = securityProperties;
		this.clock = clock;
		this.unknownUserHash = passwordEncoder.encode("unknown user placeholder");
		if (!securityProperties.requireMfa()) {
			LOG.warn("hayi.security.require-mfa=false: roles that require MFA can log in without it (local only, Decision L6)");
		}
	}

	@Transactional
	public LoginResult loginBackoffice(String identifier, String password, ClientInfo client) {
		Instant now = Instant.now(this.clock);
		Optional<User> found = findUser(identifier);
		if (found.isEmpty()) {
			this.passwordEncoder.matches(password, this.unknownUserHash);
			this.securityEvents.record(SecurityEventType.LOGIN_FAILED, null, null, mask(identifier), client, null);
			return new LoginResult.Rejected(IdentityErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		User user = found.get();
		if (user.isLocked(now)) {
			this.securityEvents.record(SecurityEventType.LOGIN_BLOCKED, user.getId(), null, client);
			return new LoginResult.Locked(IdentityErrorCode.AUTH_LOGIN_LOCKED, user.lockRemaining(now));
		}
		UserCredential credential = this.credentials.findById(user.getId()).orElse(null);
		if (!user.isActive() || credential == null || !this.passwordEncoder.matches(password, credential.getPasswordHash())) {
			return registerFailure(user, now, client);
		}
		if (!this.roles.hasAnyStoreRole(user.getId())) {
			// Persona STAFF needs a STORE role or an active StaffProfile (Identity §2.5); staff profiles come later.
			// Answered like a wrong password so this endpoint never confirms another persona's password (Decision K2).
			this.securityEvents.record(SecurityEventType.LOGIN_FAILED, user.getId(), null, null, client,
					Map.of("reason", SecurityEventLog.NO_BACKOFFICE_ACCESS));
			return new LoginResult.Rejected(IdentityErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		user.registerSuccessfulLogin(now);
		if (credential.isMustChangePassword()) {
			passwordAccepted(user, client, "PASSWORD_CHANGE");
			return new LoginResult.PasswordChangeRequired(this.tokens.issuePasswordChangeToken(user.getId()));
		}
		if (this.mfaCodes.hasActiveFactor(user.getId())) {
			passwordAccepted(user, client, "MFA");
			Mfa mfa = this.properties.mfa();
			VerificationChallenge challenge = this.challenges.save(VerificationChallenge.mfaLogin(user.getId(),
					LoginIdentifier.normalizeEmail(identifier), now, mfa.challengeTtl(), mfa.challengeMaxAttempts()));
			return new LoginResult.MfaRequired(challenge.getId());
		}
		if (this.securityProperties.requireMfa() && this.roles.requiresMfa(user.getId())) {
			passwordAccepted(user, client, "MFA_ENROLLMENT");
			return new LoginResult.MfaEnrollmentRequired(this.tokens.issueMfaEnrollmentToken(user.getId()));
		}
		this.securityEvents.record(SecurityEventType.LOGIN_SUCCESS, user.getId(), null, client);
		return new LoginResult.Authenticated(this.sessionIssuer.openBackofficeSession(user.getId(), client, now));
	}

	/** The password was right; the login continues with {@code next} before any session exists. */
	private void passwordAccepted(User user, ClientInfo client, String next) {
		this.securityEvents.record(SecurityEventType.LOGIN_SUCCESS, user.getId(), null, null, client, Map.of("next", next));
	}

	private LoginResult registerFailure(User user, Instant now, ClientInfo client) {
		LoginLockPolicy policy = this.properties.login().lockPolicy();
		int earlierFailures = this.securityEvents.countLoginFailures(user.getId(), user.failureWindowStart(now, policy));
		boolean locked = user.registerFailedLogin(now, earlierFailures, policy);
		this.securityEvents.record(SecurityEventType.LOGIN_FAILED, user.getId(), null, client);
		if (locked) {
			this.securityEvents.record(SecurityEventType.ACCOUNT_LOCKED, user.getId(), null, client);
			return new LoginResult.Locked(IdentityErrorCode.AUTH_LOGIN_LOCKED, user.lockRemaining(now));
		}
		return new LoginResult.Rejected(IdentityErrorCode.AUTH_INVALID_CREDENTIALS);
	}

	/** Backoffice v1 logs in by email; employee code and phone arrive with staff provisioning. */
	private Optional<User> findUser(String identifier) {
		if (!identifier.contains("@")) {
			return Optional.empty();
		}
		return this.identifiers
			.findByTypeAndNormalizedValue(IdentifierType.EMAIL, LoginIdentifier.normalizeEmail(identifier))
			.flatMap(found -> this.users.findByIdForUpdate(found.getUserId()));
	}

	/** {@code ow***@hayi.vn}: enough to correlate attempts, not enough to leak the identifier. */
	static String mask(String identifier) {
		String value = identifier.strip();
		int at = value.indexOf('@');
		String local = (at >= 0) ? value.substring(0, at) : value;
		String visible = local.substring(0, Math.min(2, local.length()));
		String masked = visible + "***" + ((at >= 0) ? value.substring(at) : "");
		return (masked.length() > 255) ? masked.substring(0, 255) : masked;
	}

}
