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
	/** The caller's precondition (e.g. {@code If-Match}) no longer holds. */
	PRECONDITION_FAILED,
	/** The operation needs a precondition (e.g. {@code If-Match}) the caller did not send. */
	PRECONDITION_REQUIRED,
	BUSINESS_RULE,
	TOO_MANY_REQUESTS,
	INTERNAL

}
