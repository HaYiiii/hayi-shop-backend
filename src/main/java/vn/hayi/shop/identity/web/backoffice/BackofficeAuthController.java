package vn.hayi.shop.identity.web.backoffice;

import java.time.Duration;
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
import vn.hayi.shop.identity.application.MfaEnrollment;
import vn.hayi.shop.identity.application.MfaEnrollmentService;
import vn.hayi.shop.identity.application.MfaResult;
import vn.hayi.shop.identity.application.MfaVerificationService;
import vn.hayi.shop.identity.application.RefreshResult;
import vn.hayi.shop.identity.application.SessionService;
import vn.hayi.shop.identity.application.SessionTokens;
import vn.hayi.shop.identity.application.StepUpResult;
import vn.hayi.shop.identity.application.StepUpService;
import vn.hayi.shop.platform.security.AuthCookies;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.web.spec.backoffice.api.AuthApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.FirstPasswordChangeRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.LoginRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.LoginResponseDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.MfaEnrollmentConfirmRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.MfaEnrollmentConfirmResponseDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.MfaEnrollmentRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.MfaEnrollmentResponseDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.MfaVerifyRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.StepUpRequestDto;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ErrorCode;
import vn.hayi.shop.shared.error.ThrottledException;

/** {@code /api/backoffice/v1/auth} (API 01-security §6.2); maps HTTP and cookies to the identity use cases. */
@RestController
class BackofficeAuthController implements AuthApi {

	private final LoginService login;

	private final FirstPasswordChangeService firstPasswordChange;

	private final MfaVerificationService mfaVerification;

	private final MfaEnrollmentService mfaEnrollment;

	private final StepUpService stepUp;

	private final SessionService sessions;

	private final AuthCookies cookies;

	private final CsrfTokens csrfTokens;

	private final HttpServletRequest request;

	BackofficeAuthController(LoginService login, FirstPasswordChangeService firstPasswordChange,
			MfaVerificationService mfaVerification, MfaEnrollmentService mfaEnrollment, StepUpService stepUp,
			SessionService sessions, AuthCookies cookies, CsrfTokens csrfTokens, HttpServletRequest request) {
		this.login = login;
		this.firstPasswordChange = firstPasswordChange;
		this.mfaVerification = mfaVerification;
		this.mfaEnrollment = mfaEnrollment;
		this.stepUp = stepUp;
		this.sessions = sessions;
		this.cookies = cookies;
		this.csrfTokens = csrfTokens;
		this.request = request;
	}

	@Override
	public ResponseEntity<LoginResponseDto> login(LoginRequestDto body) {
		return switch (this.login.loginBackoffice(body.getIdentifier(), body.getPassword(), client())) {
			case LoginResult.Authenticated authenticated -> ResponseEntity.ok()
				.headers(setCookies(sessionCookies(authenticated.tokens())))
				.body(new LoginResponseDto(false, null, false, null, false, null));
			case LoginResult.PasswordChangeRequired change ->
				ResponseEntity.ok(new LoginResponseDto(true, change.changeToken(), false, null, false, null));
			case LoginResult.MfaRequired mfa ->
				ResponseEntity.ok(new LoginResponseDto(false, null, true, mfa.challengeId(), false, null));
			case LoginResult.MfaEnrollmentRequired enrollment ->
				ResponseEntity.ok(new LoginResponseDto(false, null, false, null, true, enrollment.enrollmentToken()));
			case LoginResult.Rejected rejected -> throw new DomainException(rejected.code(), "Login rejected");
			case LoginResult.Locked locked -> throw loginLocked(locked.code(), locked.retryAfter());
		};
	}

	@Override
	public ResponseEntity<Void> changeFirstPassword(FirstPasswordChangeRequestDto body) {
		this.firstPasswordChange.change(body.getChangeToken(), body.getNewPassword(), client());
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<Void> verifyMfa(MfaVerifyRequestDto body) {
		return switch (this.mfaVerification.verify(body.getMfaChallengeId(), body.getCode(), client())) {
			case MfaResult.Authenticated authenticated ->
				ResponseEntity.noContent().headers(setCookies(sessionCookies(authenticated.tokens()))).build();
			case MfaResult.Enrolled enrolled -> throw new IllegalStateException("Verification never enrolls");
			case MfaResult.Rejected rejected -> throw new DomainException(rejected.code(), "MFA verification rejected");
			case MfaResult.Locked locked -> throw loginLocked(locked.code(), locked.retryAfter());
		};
	}

	@Override
	public ResponseEntity<MfaEnrollmentResponseDto> startMfaEnrollment(MfaEnrollmentRequestDto body) {
		MfaEnrollment enrollment = this.mfaEnrollment.start(body.getEnrollmentToken());
		return ResponseEntity.ok(new MfaEnrollmentResponseDto(enrollment.otpauthUri(), enrollment.secret()));
	}

	@Override
	public ResponseEntity<MfaEnrollmentConfirmResponseDto> confirmMfaEnrollment(MfaEnrollmentConfirmRequestDto body) {
		return switch (this.mfaEnrollment.confirm(body.getEnrollmentToken(), body.getCode(), client())) {
			case MfaResult.Enrolled enrolled -> ResponseEntity.ok()
				.headers(setCookies(sessionCookies(enrolled.tokens())))
				.body(new MfaEnrollmentConfirmResponseDto(enrolled.recoveryCodes()));
			case MfaResult.Authenticated authenticated -> throw new IllegalStateException("Enrollment always enrolls");
			case MfaResult.Rejected rejected -> throw new DomainException(rejected.code(), "MFA enrollment rejected");
			case MfaResult.Locked locked -> throw new IllegalStateException("Enrollment never locks the login");
		};
	}

	@Override
	public ResponseEntity<Void> stepUp(StepUpRequestDto body, String csrfHeader) {
		AuthenticatedSession session = AuthenticatedSession.current()
			.orElseThrow(() -> new DomainException(CommonErrorCode.AUTH_REQUIRED, "No session"));
		return switch (this.stepUp.stepUp(session, body.getCode(), body.getPassword(), client())) {
			case StepUpResult.Verified verified -> ResponseEntity.noContent().build();
			case StepUpResult.Rejected rejected -> throw new DomainException(rejected.code(), "Step-up rejected");
		};
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

	/** {@code 429} with {@code Retry-After}; the message names the minutes left, rounded up. */
	private static ThrottledException loginLocked(ErrorCode code, Duration retryAfter) {
		return new ThrottledException(code, "Login locked", retryAfter, Math.max(1, (retryAfter.toSeconds() + 59) / 60));
	}

}
