package vn.hayi.shop.identity.application;

import java.util.List;

import vn.hayi.shop.shared.error.ErrorCode;

/**
 * Outcome of an MFA step of login. Failures are values, not exceptions, so attempt counters and security events
 * commit even though the step is refused.
 */
public sealed interface MfaResult {

	record Authenticated(SessionTokens tokens) implements MfaResult {
	}

	/** MFA was just enabled: the recovery codes are shown this one time (Decision L5). */
	record Enrolled(SessionTokens tokens, List<String> recoveryCodes) implements MfaResult {
	}

	record Rejected(ErrorCode code) implements MfaResult {
	}

}
