package vn.hayi.shop.platform.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/**
 * Reads the access token from the surface's cookie only (Decision G2: no {@code Authorization} header, no
 * {@code localStorage}). Auth endpoints that run without an access token ignore it, so an expired cookie never
 * blocks login, refresh or logout.
 */
final class CookieBearerTokenResolver implements BearerTokenResolver {

	@Override
	public String resolve(HttpServletRequest request) {
		if (AuthEndpoints.isBeforeSession(request) || AuthEndpoints.isOnRefreshToken(request)) {
			return null;
		}
		return Surface.of(AuthEndpoints.pathOf(request))
			.map(surface -> cookie(request, surface.accessCookie()))
			.orElse(null);
	}

	static String cookie(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookie.getName().equals(name) && !cookie.getValue().isEmpty()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
