package vn.hayi.shop.platform.security;

import java.time.Duration;
import java.util.List;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Auth cookies exactly as API 01-security §3: host-only, {@code Secure}, split per surface by {@code Path}.
 * Access and refresh are {@code HttpOnly}; the CSRF cookie is readable by JS so it can be echoed in a header.
 */
@Component
public class AuthCookies {

	private final SecurityProperties properties;

	AuthCookies(SecurityProperties properties) {
		this.properties = properties;
	}

	/** Cookies of a freshly issued session or rotation; refresh and CSRF live as long as the refresh token. */
	public List<ResponseCookie> session(Surface surface, String accessToken, String refreshToken,
			Duration refreshLifetime, String csrfToken) {
		return List.of(access(surface, accessToken, this.properties.accessTokenTtl()),
				refresh(surface, refreshToken, refreshLifetime), csrf(csrfToken, refreshLifetime));
	}

	/** @param lifetime {@code null} for a browser-session cookie (re-issue without a known refresh lifetime) */
	public ResponseCookie csrf(String csrfToken, Duration lifetime) {
		ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(CsrfTokens.COOKIE, csrfToken)
			.secure(true)
			.sameSite("Lax")
			.path("/");
		if (lifetime != null) {
			cookie.maxAge(lifetime);
		}
		return cookie.build();
	}

	/** Expires every auth cookie of the surface (logout, API 01-security §3). */
	public List<ResponseCookie> cleared(Surface surface) {
		return List.of(access(surface, "", Duration.ZERO), refresh(surface, "", Duration.ZERO),
				csrf("", Duration.ZERO));
	}

	private static ResponseCookie access(Surface surface, String value, Duration maxAge) {
		return ResponseCookie.from(surface.accessCookie(), value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.path(surface.pathPrefix())
			.maxAge(maxAge)
			.build();
	}

	private static ResponseCookie refresh(Surface surface, String value, Duration maxAge) {
		return ResponseCookie.from(surface.refreshCookie(), value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Strict")
			.path(surface.authPath())
			.maxAge(maxAge)
			.build();
	}

}
