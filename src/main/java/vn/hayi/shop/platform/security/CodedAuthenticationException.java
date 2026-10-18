package vn.hayi.shop.platform.security;

import org.springframework.security.core.AuthenticationException;

import vn.hayi.shop.shared.error.ErrorCode;

/** An authentication failure with a specific client-facing code, e.g. {@code AUTH_SESSION_REVOKED}. */
public class CodedAuthenticationException extends AuthenticationException {

	private final ErrorCode code;

	public CodedAuthenticationException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public ErrorCode code() {
		return this.code;
	}

}
