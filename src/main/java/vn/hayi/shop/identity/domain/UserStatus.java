package vn.hayi.shop.identity.domain;

/** Account-level security lifecycle (Identity §2.8). A temporary lock is not a status. */
public enum UserStatus {

	ACTIVE,
	DISABLED,
	CLOSED

}
