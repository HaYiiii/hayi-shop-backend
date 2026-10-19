package vn.hayi.shop.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.identity.application.IdentityProperties.Mfa;
import vn.hayi.shop.identity.domain.IdentityErrorCode;
import vn.hayi.shop.identity.domain.RevokeReason;
import vn.hayi.shop.identity.domain.UserCredential;
import vn.hayi.shop.identity.domain.UserCredentialRepository;
import vn.hayi.shop.identity.domain.UserSession;
import vn.hayi.shop.identity.domain.UserSessionRepository;
import vn.hayi.shop.identity.infrastructure.SecurityEventLog;
import vn.hayi.shop.identity.infrastructure.SecurityEventType;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Re-authenticates the current session for actions marked S (Identity §2.4, API 01-security §8, Decision L7): by TOTP
 * when the user has MFA, by password otherwise. Each failure is an {@code MFA_FAILED} event; the 5th within 15
 * minutes revokes the session.
 */
@Service
public class StepUpService {

	private final UserSessionRepository sessions;

	private final SessionService sessionService;

	private final UserCredentialRepository credentials;

	private final MfaCodes mfaCodes;

	private final PasswordEncoder passwordEncoder;

	private final SecurityEventLog securityEvents;

	private final IdentityProperties properties;

	private final Clock clock;

	StepUpService(UserSessionRepository sessions, SessionService sessionService, UserCredentialRepository credentials,
			MfaCodes mfaCodes, PasswordEncoder passwordEncoder, SecurityEventLog securityEvents,
			IdentityProperties properties, Clock clock) {
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.credentials = credentials;
		this.mfaCodes = mfaCodes;
		this.passwordEncoder = passwordEncoder;
		this.securityEvents = securityEvents;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public StepUpResult stepUp(AuthenticatedSession caller, String code, String password, ClientInfo client) {
		Instant now = Instant.now(this.clock);
		Optional<UserSession> found = this.sessions.findByIdForUpdate(caller.sessionId())
			.filter(session -> session.isActive(now));
		if (found.isEmpty()) {
			return new StepUpResult.Rejected(CommonErrorCode.AUTH_SESSION_REVOKED);
		}
		UserSession session = found.get();
		ErrorCode failure = verify(caller, code, password, now);
		if (failure == null) {
			session.verifyStepUp(now);
			return new StepUpResult.Verified();
		}
		this.securityEvents.record(SecurityEventType.MFA_FAILED, caller.userId(), session.getId(), client);
		Mfa mfa = this.properties.mfa();
		int failures = this.securityEvents.countStepUpFailures(caller.userId(), session.getId(),
				now.minus(mfa.stepUpFailureWindow()));
		if (failures >= mfa.stepUpMaxFailures()) {
			this.sessionService.revoke(session, RevokeReason.STEP_UP_FAILED, now, client);
			return new StepUpResult.Rejected(CommonErrorCode.AUTH_SESSION_REVOKED);
		}
		return new StepUpResult.Rejected(failure);
	}

	/** {@code null} when the user proved it is them again, else the error to answer. */
	private ErrorCode verify(AuthenticatedSession caller, String code, String password, Instant now) {
		if (this.mfaCodes.hasActiveFactor(caller.userId())) {
			boolean accepted = code != null && this.mfaCodes.acceptTotp(caller.userId(), code, now);
			return accepted ? null : IdentityErrorCode.MFA_CODE_INVALID;
		}
		boolean accepted = password != null && this.credentials.findById(caller.userId())
			.map(UserCredential::getPasswordHash)
			.filter(hash -> this.passwordEncoder.matches(password, hash))
			.isPresent();
		return accepted ? null : IdentityErrorCode.AUTH_INVALID_CREDENTIALS;
	}

}
