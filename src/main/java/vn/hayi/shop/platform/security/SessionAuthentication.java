package vn.hayi.shop.platform.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentication for a request carrying a valid access token of a live session. It grants no authorities: permissions
 * are resolved per request by {@link AuthorizationInterceptor} (Decision M3).
 */
final class SessionAuthentication extends AbstractAuthenticationToken {

	private final AuthenticatedSession session;

	private final String token;

	SessionAuthentication(AuthenticatedSession session, String token) {
		super(List.of());
		this.session = session;
		this.token = token;
		setAuthenticated(true);
	}

	@Override
	public AuthenticatedSession getPrincipal() {
		return this.session;
	}

	@Override
	public String getCredentials() {
		return this.token;
	}

}
