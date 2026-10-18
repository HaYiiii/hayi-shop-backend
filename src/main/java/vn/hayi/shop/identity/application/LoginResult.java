package vn.hayi.shop.identity.application;

import java.time.Duration;
import java.util.UUID;

import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Outcome of a login attempt. Failures are values, not exceptions, so the failure counter and security events
 * commit even though the login is refused.
 */
public sealed interface LoginResult {

	record Authenticated(SessionTokens tokens) implements LoginResult {
	}

	/** Temporary password: no session until it is replaced (Decision K3). */
	record PasswordChangeRequired(String changeToken) implements LoginResult {
	}

	/** An active MFA factor: no session until the challenge is answered (Decision L1). */
	record MfaRequired(UUID challengeId) implements LoginResult {
	}

	/** The user's role requires MFA and none is enabled: enroll first (Decision D4, L6). */
	record MfaEnrollmentRequired(String enrollmentToken) implements LoginResult {
	}

	record Rejected(ErrorCode code) implements LoginResult {
	}

	record Locked(ErrorCode code, Duration retryAfter) implements LoginResult {
	}

}
