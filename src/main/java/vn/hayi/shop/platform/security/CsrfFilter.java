package vn.hayi.shop.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;

/**
 * Double-submit CSRF check (API 01-security §3.1): every unsafe request that rides on auth cookies must echo the
 * {@code hy_csrf} cookie in {@code X-CSRF-Token}, and the token must belong to the caller's session. Failures get
 * {@code 403 CSRF_INVALID} and a {@code CSRF_VALIDATION_FAILED} security event.
 * <p>
 * Runs after authentication: requests without a session (public, login) are not checked; refresh and logout carry
 * only the refresh cookie, so the session match is completed by the endpoint using
 * {@link CsrfTokens#VERIFIED_SESSION_ATTRIBUTE}.
 */
final class CsrfFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private final CsrfTokens tokens;

	private final SecurityEventSink events;

	private final HandlerExceptionResolver exceptionResolver;

	CsrfFilter(CsrfTokens tokens, SecurityEventSink events, HandlerExceptionResolver exceptionResolver) {
		this.tokens = tokens;
		this.events = events;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<AuthenticatedSession> session = AuthenticatedSession.current();
		boolean ridesOnCookies = session.isPresent() || AuthEndpoints.isOnRefreshToken(request);
		if (SAFE_METHODS.contains(request.getMethod()) || !ridesOnCookies) {
			chain.doFilter(request, response);
			return;
		}
		String header = request.getHeader(CsrfTokens.HEADER);
		String cookie = CookieBearerTokenResolver.cookie(request, CsrfTokens.COOKIE);
		Optional<UUID> tokenSession = sameValue(header, cookie) ? this.tokens.sessionOf(header) : Optional.empty();
		boolean valid = tokenSession.isPresent()
				&& session.map(current -> current.sessionId().equals(tokenSession.get())).orElse(true);
		if (!valid) {
			this.events.csrfValidationFailed(session.map(AuthenticatedSession::userId).orElse(null),
					session.map(AuthenticatedSession::sessionId).orElse(null), ClientInfo.from(request));
			this.exceptionResolver.resolveException(request, response, null,
					new DomainException(CommonErrorCode.CSRF_INVALID, "CSRF token missing or not bound to the session"));
			return;
		}
		request.setAttribute(CsrfTokens.VERIFIED_SESSION_ATTRIBUTE, tokenSession.get());
		chain.doFilter(request, response);
	}

	private static boolean sameValue(String header, String cookie) {
		return header != null && cookie != null
				&& MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8));
	}

}
