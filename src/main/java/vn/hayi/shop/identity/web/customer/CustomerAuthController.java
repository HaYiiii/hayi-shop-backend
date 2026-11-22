package vn.hayi.shop.identity.web.customer;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.identity.application.CustomerAccountService;
import vn.hayi.shop.identity.application.CustomerPasswordService;
import vn.hayi.shop.identity.application.CustomerRegistrationService;
import vn.hayi.shop.identity.application.LoginResult;
import vn.hayi.shop.identity.application.LoginService;
import vn.hayi.shop.identity.application.RefreshResult;
import vn.hayi.shop.identity.application.SessionService;
import vn.hayi.shop.identity.application.SessionTokens;
import vn.hayi.shop.identity.application.OtpService;
import vn.hayi.shop.platform.security.AuthCookies;
import vn.hayi.shop.platform.security.AuthRateLimiter;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.web.spec.customer.model.DeviceSessionDto;
import vn.hayi.shop.platform.web.spec.customer.model.DeviceSessionListDto;
import vn.hayi.shop.identity.application.DeviceSessionService;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.CsrfTokens;
import vn.hayi.shop.platform.security.Surface;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.spec.customer.api.CustomerAuthApi;
import vn.hayi.shop.platform.web.spec.customer.model.CustomerLoginRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.CustomerLoginResponseDto;
import vn.hayi.shop.platform.web.spec.customer.model.CustomerMeDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.PasswordChangeRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.PasswordResetRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpSentDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpVerifiedDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpVerifyRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.RegisterRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.RegisteredDto;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.ThrottledException;

/**
 * Customer auth surface (API 01-security §6.1): registration by phone OTP (Decision AN), login, refresh, logout, CSRF
 * and the caller (Decision AO).
 */
@RestController
class CustomerAuthController implements CustomerAuthApi {

	private final OtpService otps;

	private final CustomerRegistrationService registrations;

	private final HttpServletRequest request;

	private final LoginService login;

	private final SessionService sessions;

	private final CustomerAccountService accounts;

	private final AuthCookies cookies;

	private final CsrfTokens csrfTokens;

	private final AuthRateLimiter rateLimits;

	private final CustomerPasswordService passwords;

	private final DeviceSessionService devices;

	CustomerAuthController(OtpService otps, CustomerRegistrationService registrations,
			HttpServletRequest request, LoginService login, SessionService sessions, CustomerAccountService accounts,
			AuthCookies cookies, CsrfTokens csrfTokens, AuthRateLimiter rateLimits, CustomerPasswordService passwords,
			DeviceSessionService devices) {
		this.passwords = passwords;
		this.devices = devices;
		this.otps = otps;
		this.registrations = registrations;
		this.request = request;
		this.login = login;
		this.sessions = sessions;
		this.accounts = accounts;
		this.cookies = cookies;
		this.csrfTokens = csrfTokens;
		this.rateLimits = rateLimits;
	}

	// --- session (Decision AO) ---------------------------------------------------------------------------------

	@Override
	public ResponseEntity<CustomerLoginResponseDto> loginCustomer(CustomerLoginRequestDto body) {
		ClientInfo client = client();
		this.rateLimits.checkLogin(Surface.CUSTOMER, client);
		return switch (this.login.loginCustomer(body.getPhone(), body.getPassword(), client)) {
			case LoginResult.Authenticated authenticated -> ResponseEntity.ok()
				.headers(setCookies(sessionCookies(authenticated.tokens())))
				.body(new CustomerLoginResponseDto(false, null));
			case LoginResult.Rejected rejected -> throw new DomainException(rejected.code(), "Login rejected");
			case LoginResult.Locked locked -> throw new ThrottledException(locked.code(), "Login locked",
					locked.retryAfter(), Math.max(1, (locked.retryAfter().toSeconds() + 59) / 60));
			default -> throw new IllegalStateException("Customer login has no further step");
		};
	}

	@Override
	public ResponseEntity<Void> refreshCustomer(String refreshToken, String csrfHeader) {
		ClientInfo client = client();
		this.rateLimits.checkRefresh(Surface.CUSTOMER, csrfSession(), client);
		return switch (this.sessions.refresh(Surface.CUSTOMER, refreshToken, csrfSession(), client)) {
			case RefreshResult.Rotated rotated ->
				ResponseEntity.noContent().headers(setCookies(sessionCookies(rotated.tokens()))).build();
			case RefreshResult.Rejected rejected -> throw new DomainException(rejected.code(), "Refresh rejected");
		};
	}

	@Override
	public ResponseEntity<Void> logoutCustomer(String refreshToken, String csrfHeader) {
		if (!this.sessions.logout(Surface.CUSTOMER, refreshToken, csrfSession(), client())) {
			throw new DomainException(CommonErrorCode.CSRF_INVALID, "CSRF token of another session");
		}
		return ResponseEntity.noContent().headers(setCookies(this.cookies.cleared(Surface.CUSTOMER))).build();
	}

	@Override
	public ResponseEntity<Void> issueCustomerCsrfToken() {
		AuthenticatedSession session = AuthenticatedSession.require();
		ResponseCookie csrf = this.cookies.csrf(Surface.CUSTOMER, this.csrfTokens.issue(session.sessionId()), null);
		return ResponseEntity.noContent().headers(setCookies(List.of(csrf))).build();
	}

	@Override
	public ResponseEntity<CustomerMeDto> getCustomerMe() {
		CustomerAccountService.CustomerAccount account = this.accounts.account(AuthenticatedSession.require().userId());
		return ResponseEntity.ok(new CustomerMeDto(account.userId(), account.customerId(), account.displayName(),
				account.phoneMasked()));
	}

	private List<ResponseCookie> sessionCookies(SessionTokens tokens) {
		return this.cookies.session(Surface.CUSTOMER, tokens.accessToken(), tokens.refreshToken(),
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

	@Override
	public ResponseEntity<DeviceSessionListDto> listCustomerSessions() {
		AuthenticatedSession session = AuthenticatedSession.require();
		return ResponseEntity.ok(new DeviceSessionListDto(this.devices
			.list(session.userId(), session.persona(), session.sessionId())
			.stream()
			.map(device -> new DeviceSessionDto(device.sessionId(), device.userAgent(), device.ipAddress(),
					device.createdAt().atOffset(java.time.ZoneOffset.UTC),
					device.lastActiveAt().atOffset(java.time.ZoneOffset.UTC), device.current()))
			.toList()));
	}

	@Override
	public ResponseEntity<Void> revokeCustomerSession(UUID sessionId, String xCsrfToken) {
		AuthenticatedSession session = AuthenticatedSession.require();
		this.devices.revoke(session.userId(), session.persona(), session.sessionId(), sessionId, client());
		return ResponseEntity.noContent().build();
	}

	// --- password (Decision AS) --------------------------------------------------------------------------------

	@Override
	public ResponseEntity<Void> resetPassword(PasswordResetRequestDto body) {
		this.passwords.reset(body.getVerificationToken(), body.getNewPassword(), client());
		return ResponseEntity.noContent().build();
	}

	@Override
	public ResponseEntity<Void> changePassword(PasswordChangeRequestDto body, String xCsrfToken) {
		AuthenticatedSession session = AuthenticatedSession.require();
		this.passwords.change(session.userId(), session.sessionId(), body.getCurrentPassword(), body.getNewPassword(),
				client());
		return ResponseEntity.noContent().build();
	}

	// --- registration (Decision AN) ----------------------------------------------------------------------------

	@Override
	public ResponseEntity<OtpSentDto> requestOtp(OtpRequestDto body) {
		String phone = OtpService.normalizePhone(body.getPhone())
			.orElseThrow(() -> new InvalidFieldsException(List.of(new InvalidFieldsException.Violation("phone",
					"PHONE_INVALID"))));
		OtpService.Sent sent = this.otps.request(phone, body.getPurpose().getValue(), client());
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(new OtpSentDto(sent.challengeId(), sent.expiresAt().atOffset(ZoneOffset.UTC),
					sent.resendAfterSeconds()));
	}

	@Override
	public ResponseEntity<OtpVerifiedDto> verifyOtp(OtpVerifyRequestDto body) {
		OtpService.Verified verified = this.otps.verify(body.getChallengeId(), body.getCode(), client());
		return ResponseEntity
			.ok(new OtpVerifiedDto(verified.verificationToken(), verified.expiresAt().atOffset(ZoneOffset.UTC)));
	}

	@Override
	public ResponseEntity<RegisteredDto> registerCustomer(RegisterRequestDto body) {
		// referralCode is accepted and ignored until Loyalty (Decision AN4).
		var customerId = this.registrations.register(new CustomerRegistrationService.Registration(
				body.getVerificationToken(), body.getDisplayName().strip(), body.getPassword(),
				body.getCurrentPassword()));
		return ResponseEntity.status(HttpStatus.CREATED).body(new RegisteredDto(customerId));
	}

	private ClientInfo client() {
		return ClientInfo.from(this.request);
	}

}
