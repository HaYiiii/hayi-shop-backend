package vn.hayi.shop.shared.error;

/**
 * Category of an error, independent of any transport. {@code platform.web} maps it to an HTTP status
 * (API README §6); the domain never sees HTTP.
 */
public enum ErrorKind {

	VALIDATION,
	UNAUTHENTICATED,
	FORBIDDEN,
	NOT_FOUND,
	CONFLICT,
	BUSINESS_RULE,
	INTERNAL

}
