package vn.hayi.shop.identity.web.backoffice;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.identity.application.FirstPasswordChangeService;
import vn.hayi.shop.identity.application.LoginResult;
import vn.hayi.shop.identity.application.LoginService;
import vn.hayi.shop.identity.application.RefreshResult;
import vn.hayi.shop.identity.application.SessionService;
import vn.hayi.shop.identity.application.SessionTokens;
import vn.hayi.shop.platform.security.AuthCookies;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.web.spec.backoffice.api.AuthApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.FirstPasswordChangeRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.LoginRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.LoginResponseDto;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ThrottledException;

/** {@code /api/backoffice/v1/auth} (API 01-security §6.2); maps HTTP and cookies to the identity use cases. */
@RestController
class BackofficeAuthController implements AuthApi {

	private final LoginService login;

	private final FirstPasswordChangeService firstPasswordChange;

	private final SessionService sessions;

	private final AuthCookies cookies;

	private final CsrfTokens csrfTokens;

	private final HttpServletRequest request;

	BackofficeAuthController(LoginService login, FirstPasswordChangeService firstPasswordChange,
			SessionService sessions, AuthCookies cookies, CsrfTokens csrfTokens, HttpServletRequest request) {
		this.login = login;
		this.firstPasswordChange = firstPasswordChange;
		this.sessions = sessions;
		this.cookies = cookies;
		this.csrfTokens = csrfTokens;
		this.request = request;
	}

	@Override
	public ResponseEntity<LoginResponseDto> login(LoginRequestDto body) {
		return switch (this.login.loginBackoffice(body.getIdentifier(), body.getPassword(), client())) {
			case LoginResult.Authenticated authenticated ->
				ResponseEntity.ok().headers(setCookies(sessionCookies(authenticated.tokens()))).body(new LoginResponseDto(false, null));
			case LoginResult.PasswordChangeRequired change ->
				ResponseEntity.ok(new LoginResponseDto(true, change.changeToken()));
			case LoginResult.Rejected rejected -> throw new DomainException(rejected.code(), "Login rejected");
			case LoginResult.Locked locked -> throw new ThrottledException(locked.code(),
					"Login locked", locked.retryAfter(), Math.max(1, (locked.retryAfter().toSeconds() + 59) / 60));
		};
	}

	@Override
	public ResponseEntity<Void> changeFirstPassword(FirstPasswordChangeRequestDto body) {
		this.firstPasswordChange.change(body.getChangeToken(), body.getNewPassword(), client());
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<Void> refresh(String refreshToken, String csrfHeader) {
		return switch (this.sessions.refresh(refreshToken, csrfSession(), client())) {
			case RefreshResult.Rotated rotated ->
				ResponseEntity.noContent().headers(setCookies(sessionCookies(rotated.tokens()))).build();
			case RefreshResult.Rejected rejected -> throw new DomainException(rejected.code(), "Refresh rejected");
		};
	}

	@Override
	public ResponseEntity<Void> logout(String refreshToken, String csrfHeader) {
		if (!this.sessions.logout(refreshToken, csrfSession(), client())) {
			throw new DomainException(CommonErrorCode.CSRF_INVALID, "CSRF token of another session");
		}
		return ResponseEntity.noContent().headers(setCookies(this.cookies.cleared(Surface.BACKOFFICE))).build();
	}

	@Override
	public ResponseEntity<Void> issueCsrfToken() {
		AuthenticatedSession session = AuthenticatedSession.current()
			.orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "No session"));
		ResponseCookie csrf = this.cookies.csrf(this.csrfTokens.issue(session.sessionId()), null);
		return ResponseEntity.noContent().headers(setCookies(List.of(csrf))).build();
	}

	private List<ResponseCookie> sessionCookies(SessionTokens tokens) {
		return this.cookies.session(Surface.BACKOFFICE, tokens.accessToken(), tokens.refreshToken(),
				tokens.refreshLifetime(), tokens.csrfToken());
	}

	private static HttpHeaders setCookies(List<ResponseCookie> cookies) {
		HttpHeaders headers = new HttpHeaders();
		cookies.forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie.toString()));
		return headers;
	}

	/** Set by the CSRF filter once the double-submit token checked out. */
	private UUID csrfSession() {
		return (UUID) this.request.getAttribute(CsrfTokens.VERIFIED_SESSION_ATTRIBUTE);
	}

	private ClientInfo client() {
		return ClientInfo.from(this.request);
	}

}
