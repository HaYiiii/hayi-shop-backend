package vn.hayi.shop.platform.security;

import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Auth endpoints that do not run on an access token (API 01-security §6). Login, first password change and the MFA
 * steps of login happen before any session exists, so they need neither access token nor CSRF; refresh and logout
 * run on the refresh cookie and still need the CSRF header (§3.1).
 */
final class AuthEndpoints {

	private static final Set<String> BEFORE_SESSION = Set.of("/login", "/password/first-change", "/mfa/verify",
			"/mfa/enroll", "/mfa/enroll/confirm");

	private static final Set<String> ON_REFRESH_TOKEN = Set.of("/refresh", "/logout");

	/** Ant patterns for {@code permitAll}. */
	static final String[] PUBLIC_PATTERNS = { "/api/*/v1/auth/login", "/api/*/v1/auth/password/first-change",
			"/api/*/v1/auth/mfa/verify", "/api/*/v1/auth/mfa/enroll", "/api/*/v1/auth/mfa/enroll/confirm",
			"/api/*/v1/auth/refresh", "/api/*/v1/auth/logout" };

	private AuthEndpoints() {
	}

	static boolean isBeforeSession(HttpServletRequest request) {
		return authEndpoint(request).filter(BEFORE_SESSION::contains).isPresent();
	}

	static boolean isOnRefreshToken(HttpServletRequest request) {
		return authEndpoint(request).filter(ON_REFRESH_TOKEN::contains).isPresent();
	}

	/** The part after {@code /api/{surface}/v1/auth}, if the request targets an auth endpoint. */
	private static Optional<String> authEndpoint(HttpServletRequest request) {
		String path = pathOf(request);
		return Surface.of(path)
			.filter(surface -> path.startsWith(surface.authPath() + "/"))
			.map(surface -> path.substring(surface.authPath().length()));
	}

	static String pathOf(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}

}
