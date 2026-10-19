package vn.hayi.shop.identity.domain;

import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ErrorKind;

/** Error codes of Identity & Access (API README §6, Decision K2, K4, L1). */
public enum IdentityErrorCode implements ErrorCode {

	AUTH_INVALID_CREDENTIALS(ErrorKind.UNAUTHENTICATED),
	AUTH_LOGIN_LOCKED(ErrorKind.TOO_MANY_REQUESTS),
	AUTH_PASSWORD_POLICY_VIOLATION(ErrorKind.BUSINESS_RULE),
	MFA_CODE_INVALID(ErrorKind.UNAUTHENTICATED),
	MFA_CHALLENGE_LOCKED(ErrorKind.UNAUTHENTICATED);

	private final ErrorKind kind;

	IdentityErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
