package vn.hayi.shop.shared.error;

/**
 * Error codes shared by every surface (API README §6).
 */
public enum CommonErrorCode implements ErrorCode {

	VALIDATION_FAILED(ErrorKind.VALIDATION),
	AUTH_REQUIRED(ErrorKind.UNAUTHENTICATED),
	AUTH_TOKEN_EXPIRED(ErrorKind.UNAUTHENTICATED),
	AUTH_SESSION_REVOKED(ErrorKind.UNAUTHENTICATED),
	PERMISSION_DENIED(ErrorKind.FORBIDDEN),
	BRANCH_SCOPE_DENIED(ErrorKind.FORBIDDEN),
	CSRF_INVALID(ErrorKind.FORBIDDEN),
	STEP_UP_REQUIRED(ErrorKind.FORBIDDEN),
	INTERNAL_ERROR(ErrorKind.INTERNAL);

	private final ErrorKind kind;

	CommonErrorCode(ErrorKind kind) {
		this.kind = kind;
	}

	@Override
	public ErrorKind kind() {
		return this.kind;
	}

}
