package vn.hayi.shop.identity.domain;

/** Challenge lifecycle (Identity §2.3). */
public enum VerificationStatus {

	PENDING,
	VERIFIED,
	CONSUMED,
	EXPIRED,
	FAILED

}
