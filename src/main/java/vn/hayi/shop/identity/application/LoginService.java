package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginIdentifier;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.identity.domain.LoginLockPolicy;
import vn.hayi.shop.identity.domain.RefreshToken;
import vn.hayi.shop.identity.domain.SessionChannel;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.infrastructure.RoleQueries;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.actor.Persona;

/**
 * Backoffice login with email + password (Identity §2.2, §2.5, API 01-security §6.2–6.3, Decision K2–K3).
 * <p>
 * Unknown identifier and wrong password look the same to the caller. MFA for STORE roles (Decision D4) is added by
 * the next slice (#14).
 */
@Service
public class LoginService {

	private final LoginIdentifierRepository identifiers;

	private final UserRepository users;

	private final UserCredentialRepository credentials;

	private final UserSessionRepository sessions;

	private final RoleQueries roles;

	private final SessionIssuer sessionIssuer;

	private final Tokens tokens;

	private final PasswordEncoder passwordEncoder;

	private final SecurityEventLog securityEvents;

	private final IdentityProperties properties;

	private final Clock clock;

	/** Compared against when the user does not exist, so both failures take the same time. */
	private final String unknownUserHash;

	LoginService(LoginIdentifierRepository identifiers, UserRepository users, UserCredentialRepository credentials,
			UserSessionRepository sessions, RoleQueries roles, SessionIssuer sessionIssuer, Tokens tokens,
			PasswordEncoder passwordEncoder, SecurityEventLog securityEvents, IdentityProperties properties,
			Clock clock) {
		this.identifiers = identifiers;
		this.users = users;
		this.credentials = credentials;
		this.sessions = sessions;
		this.roles = roles;
		this.sessionIssuer = sessionIssuer;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.securityEvents = securityEvents;
		this.properties = properties;
		this.clock = clock;
		this.unknownUserHash = passwordEncoder.encode("unknown user placeholder");
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
			this.securityEvents.record(SecurityEventType.LOGIN_SUCCESS, user.getId(), null, null, client,
					Map.of("next", "PASSWORD_CHANGE"));
			return new LoginResult.PasswordChangeRequired(this.tokens.issuePasswordChangeToken(user.getId()));
		}
		UserSession session = this.sessions.save(UserSession.open(user.getId(), Persona.STAFF,
				SessionChannel.BACKOFFICE_WEB, client.ipAddress(), client.userAgent(), now,
				this.properties.backoffice().absoluteLifetime()));
		RefreshToken refreshToken = this.sessionIssuer.newRefreshToken(session, now);
		this.securityEvents.record(SecurityEventType.LOGIN_SUCCESS, user.getId(), session.getId(), client);
		this.securityEvents.record(SecurityEventType.SESSION_CREATED, user.getId(), session.getId(), client);
		return new LoginResult.Authenticated(this.sessionIssuer.tokensFor(session, refreshToken, now));
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
