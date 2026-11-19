package vn.hayi.shop.identity.web.customer;

import java.time.ZoneOffset;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.identity.application.CustomerRegistrationService;
import vn.hayi.shop.identity.application.OtpService;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.ClientIpResolver;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.spec.customer.api.CustomerAuthApi;
import vn.hayi.shop.platform.web.spec.customer.model.OtpRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpSentDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpVerifiedDto;
import vn.hayi.shop.platform.web.spec.customer.model.OtpVerifyRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.RegisterRequestDto;
import vn.hayi.shop.platform.web.spec.customer.model.RegisteredDto;

/** Customer registration by phone OTP (API 01-security §6.1, Identity §2.11, Decision AN). No sign-in needed. */
@RestController
class CustomerAuthController implements CustomerAuthApi {

	private final OtpService otps;

	private final CustomerRegistrationService registrations;

	private final ClientIpResolver ips;

	private final HttpServletRequest request;

	CustomerAuthController(OtpService otps, CustomerRegistrationService registrations, ClientIpResolver ips,
			HttpServletRequest request) {
		this.otps = otps;
		this.registrations = registrations;
		this.ips = ips;
		this.request = request;
	}

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
		return new ClientInfo(this.ips.resolve(this.request), this.request.getHeader("User-Agent"));
	}

}
