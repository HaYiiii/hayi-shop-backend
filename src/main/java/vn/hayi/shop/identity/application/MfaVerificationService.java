package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.LoginLockPolicy;
import vn.hayi.shop.identity.domain.User;
import vn.hayi.shop.identity.domain.UserRepository;
import vn.hayi.shop.identity.domain.VerificationChallenge;
import vn.hayi.shop.identity.domain.VerificationChallengeRepository;
import vn.hayi.shop.identity.domain.VerificationPurpose;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.error.CommonErrorCode;

/**
 * The MFA step of Backoffice login (API 01-security §6.2–6.3, Decision L1): a TOTP or recovery code answers the
 * login's challenge and opens the session. The 5th wrong code locks the challenge, and the 10th of the user within 15
 * minutes, across challenges, locks the login; wrong codes do not count towards the password failures.
 */
@Service
public class MfaVerificationService {

	private final VerificationChallengeRepository challenges;

	private final UserRepository users;

	private final MfaCodes mfaCodes;

	private final SessionIssuer sessionIssuer;

	private final SecurityEventLog securityEvents;

	private final IdentityProperties properties;

	private final Clock clock;

	MfaVerificationService(VerificationChallengeRepository challenges, UserRepository users, MfaCodes mfaCodes,
			SessionIssuer sessionIssuer, SecurityEventLog securityEvents, IdentityProperties properties, Clock clock) {
		this.challenges = challenges;
		this.users = users;
		this.mfaCodes = mfaCodes;
		this.sessionIssuer = sessionIssuer;
		this.securityEvents = securityEvents;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public MfaResult verify(UUID challengeId, String code, ClientInfo client) {
		Instant now = Instant.now(this.clock);
		// The user row first, then the challenge: the order login takes when it replaces the user's open challenges.
		Optional<User> found = this.challenges.findUserIdByIdAndPurpose(challengeId, VerificationPurpose.MFA_LOGIN)
			.flatMap(this.users::findByIdForUpdate);
		Optional<VerificationChallenge> challenge = found.flatMap(user -> this.challenges.findByIdForUpdate(challengeId));
		if (challenge.isEmpty()) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		User user = found.get();
		if (user.isLocked(now)) {
			return new MfaResult.Locked(IdentityErrorCode.AUTH_LOGIN_LOCKED, user.lockRemaining(now));
		}
		if (challenge.get().isFailed()) {
			return new MfaResult.Rejected(IdentityErrorCode.MFA_CHALLENGE_LOCKED);
		}
		if (!challenge.get().isOpen(now) || !user.isActive()) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		if (!this.mfaCodes.acceptTotpOrRecoveryCode(user.getId(), code, now)) {
			return wrongCode(user, challenge.get(), now, client);
		}
		challenge.get().consume(now);
		return new MfaResult.Authenticated(this.sessionIssuer.openBackofficeSession(user.getId(), client, now));
	}

	private MfaResult wrongCode(User user, VerificationChallenge challenge, Instant now, ClientInfo client) {
		LoginLockPolicy policy = this.properties.mfa().loginLockPolicy();
		int earlierFailures = this.securityEvents.countLoginMfaFailures(user.getId(), user.failureWindowStart(now, policy));
		this.securityEvents.record(SecurityEventType.MFA_FAILED, user.getId(), null, null, client,
				Map.of("stage", SecurityEventLog.LOGIN_STAGE));
		boolean challengeLocked = challenge.registerWrongCode();
		if (user.registerFailedMfaCode(now, earlierFailures, policy)) {
			this.securityEvents.record(SecurityEventType.ACCOUNT_LOCKED, user.getId(), null, client);
			return new MfaResult.Locked(IdentityErrorCode.AUTH_LOGIN_LOCKED, user.lockRemaining(now));
		}
		return new MfaResult.Rejected(challengeLocked ? IdentityErrorCode.MFA_CHALLENGE_LOCKED
				: IdentityErrorCode.MFA_CODE_INVALID);
	}

}
