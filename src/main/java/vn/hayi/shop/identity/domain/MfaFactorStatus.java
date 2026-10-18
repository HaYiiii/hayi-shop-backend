package vn.hayi.shop.identity.domain;

/** {@code iam_mfa_factor.status} (ERD 01): a factor is usable once its first code confirmed it. */
public enum MfaFactorStatus {

	PENDING,
	ACTIVE,
	DISABLED

}
