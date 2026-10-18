package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.domain.IdentityErrorCode;
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
 * login's challenge and opens the session. The 5th wrong code locks the challenge; wrong codes do not count towards
 * the login lock.
 */
@Service
public class MfaVerificationService {

	private final VerificationChallengeRepository challenges;

	private final UserRepository users;

	private final MfaCodes mfaCodes;

	private final SessionIssuer sessionIssuer;

	private final SecurityEventLog securityEvents;

	private final Clock clock;

	MfaVerificationService(VerificationChallengeRepository challenges, UserRepository users, MfaCodes mfaCodes,
			SessionIssuer sessionIssuer, SecurityEventLog securityEvents, Clock clock) {
		this.challenges = challenges;
		this.users = users;
		this.mfaCodes = mfaCodes;
		this.sessionIssuer = sessionIssuer;
		this.securityEvents = securityEvents;
		this.clock = clock;
	}

	@Transactional
	public MfaResult verify(UUID challengeId, String code, ClientInfo client) {
		Instant now = Instant.now(this.clock);
		Optional<VerificationChallenge> found = this.challenges.findByIdForUpdate(challengeId)
			.filter(challenge -> challenge.getPurpose() == VerificationPurpose.MFA_LOGIN);
		if (found.isEmpty()) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		VerificationChallenge challenge = found.get();
		if (challenge.isFailed()) {
			return new MfaResult.Rejected(IdentityErrorCode.MFA_CHALLENGE_LOCKED);
		}
		Optional<User> user = this.users.findByIdForUpdate(challenge.getUserId())
			.filter(candidate -> candidate.isActive() && !candidate.isLocked(now));
		if (!challenge.isOpen(now) || user.isEmpty()) {
			return new MfaResult.Rejected(CommonErrorCode.AUTH_REQUIRED);
		}
		UUID userId = challenge.getUserId();
		if (!this.mfaCodes.acceptTotpOrRecoveryCode(userId, code, now)) {
			this.securityEvents.record(SecurityEventType.MFA_FAILED, userId, null, client);
			return new MfaResult.Rejected(challenge.registerWrongCode() ? IdentityErrorCode.MFA_CHALLENGE_LOCKED
					: IdentityErrorCode.MFA_CODE_INVALID);
		}
		challenge.consume(now);
		return new MfaResult.Authenticated(this.sessionIssuer.openBackofficeSession(userId, client, now));
	}

}
