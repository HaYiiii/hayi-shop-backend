package vn.hayi.shop.identity.application;

import java.time.Duration;

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

	record Rejected(ErrorCode code) implements LoginResult {
	}

	record Locked(Duration retryAfter) implements LoginResult {
	}

}
