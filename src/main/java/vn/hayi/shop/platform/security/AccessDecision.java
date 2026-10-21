package vn.hayi.shop.platform.security;

/** Outcome of a {@link PermissionEvaluator} check, mapped to the Problem Details code of API README §6. */
public enum AccessDecision {

	GRANTED,
	/** {@code 403 PERMISSION_DENIED}. */
	PERMISSION_DENIED,
	/** {@code 403 BRANCH_SCOPE_DENIED}. */
	BRANCH_SCOPE_DENIED

}
