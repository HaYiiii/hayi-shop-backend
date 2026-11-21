package vn.hayi.shop.platform.security;

import java.util.Optional;

import vn.hayi.shop.shared.actor.Persona;

/**
 * An authenticated API surface (API README §2, 01-security §3): its path, JWT audience, cookies and persona.
 * Cookies are split per surface by {@code Path}, so a browser can hold a Customer and a Backoffice session at once
 * and a token is never sent to the wrong surface.
 */
public enum Surface {

	BACKOFFICE("/api/backoffice", "hayi-backoffice", "hy_b_at", "hy_b_rt", "hy_b_csrf", Persona.STAFF),
	CUSTOMER("/api/customer", "hayi-customer", "hy_c_at", "hy_c_rt", "hy_c_csrf", Persona.CUSTOMER);

	private final String pathPrefix;

	private final String audience;

	private final String accessCookie;

	private final String refreshCookie;

	private final String csrfCookie;

	private final Persona persona;

	Surface(String pathPrefix, String audience, String accessCookie, String refreshCookie, String csrfCookie,
			Persona persona) {
		this.pathPrefix = pathPrefix;
		this.audience = audience;
		this.accessCookie = accessCookie;
		this.refreshCookie = refreshCookie;
		this.csrfCookie = csrfCookie;
		this.persona = persona;
	}

	public static Optional<Surface> of(String requestPath) {
		for (Surface surface : values()) {
			if (requestPath.equals(surface.pathPrefix) || requestPath.startsWith(surface.pathPrefix + "/")) {
				return Optional.of(surface);
			}
		}
		return Optional.empty();
	}

	public String pathPrefix() {
		return this.pathPrefix;
	}

	/** {@code /api/{surface}/v1/auth}: scope of the refresh cookie. */
	public String authPath() {
		return this.pathPrefix + "/v1/auth";
	}

	public String audience() {
		return this.audience;
	}

	public String accessCookie() {
		return this.accessCookie;
	}

	public String refreshCookie() {
		return this.refreshCookie;
	}

	/** The CSRF double-submit cookie of this surface (Decision AP1): one per surface, so two logins never clash. */
	public String csrfCookie() {
		return this.csrfCookie;
	}

	public Persona persona() {
		return this.persona;
	}

}
