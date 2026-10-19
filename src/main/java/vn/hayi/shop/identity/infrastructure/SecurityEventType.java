package vn.hayi.shop.identity.infrastructure;

/** Security events written in this slice (Identity §2.9, API 01-security §9). */
public enum SecurityEventType {

	LOGIN_SUCCESS,
	LOGIN_FAILED,
	LOGIN_BLOCKED,
	ACCOUNT_LOCKED,
	PASSWORD_CHANGED,
	SESSION_CREATED,
	SESSION_REVOKED,
	REFRESH_TOKEN_REUSE_DETECTED,
	CSRF_VALIDATION_FAILED,
	MFA_ENABLED,
	MFA_FAILED

}
