package vn.hayi.shop.platform.security;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ErrorCode;

/** Maps authentication failures to the 401 codes of API README §6. */
public final class AuthErrors {

	private AuthErrors() {
	}

	public static ErrorCode codeOf(AuthenticationException ex) {
		if (ex instanceof CodedAuthenticationException coded) {
			return coded.code();
		}
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof JwtValidationException invalid
					&& invalid.getErrors().stream().anyMatch(error -> Tokens.EXPIRED.equals(error.getErrorCode()))) {
				return CommonErrorCode.AUTH_TOKEN_EXPIRED;
			}
		}
		return CommonErrorCode.AUTH_REQUIRED;
	}

}
