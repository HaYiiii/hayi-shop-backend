package vn.hayi.shop.identity.domain;

/** Why a session ended ({@code iam_user_session.revoke_reason}, ERD 01). */
public enum RevokeReason {

	LOGOUT,
	REUSE_DETECTED,
	OFFBOARDED,
	ADMIN,
	PASSWORD_CHANGED

}
