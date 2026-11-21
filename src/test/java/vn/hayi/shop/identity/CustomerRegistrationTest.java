package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.customer.api.CustomerProfiles;
import vn.hayi.shop.identity.domain.IdentifierType;
import vn.hayi.shop.identity.domain.LoginIdentifierRepository;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.platform.security.Tokens;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A Customer registers with a phone OTP (Identity §2.11, API 01-security §6.1, §6.3, Decision AN): a new phone, a
 * phone that already belongs to a Staff member, the OTP rules and limits, and the all-or-nothing transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class,
		CapturingOtpSender.Config.class })
class CustomerRegistrationTest {

	static final String AUTH = "/api/customer/v1/auth";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	CapturingOtpSender sms;

	@MockitoSpyBean
	CustomerProfiles profiles;

	@MockitoSpyBean
	LoginIdentifierRepository identifiers;

	@Autowired
	Tokens tokens;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	// --- new phone (AN4) ---------------------------------------------------------------------------------------

	@Test
	void aNewPhoneRegistersWithItsOwnPasswordAndNoSession() throws Exception {
		String national = freshNationalPhone();
		String e164 = "+84" + national.substring(1);
		String ip = freshIp();

		MockHttpServletResponse sent = requestOtp("0" + national.substring(1, 4) + " " + national.substring(4), ip);
		assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(202);
		assertThat(JsonPath.<Integer>read(sent.getContentAsString(), "$.resendAfterSeconds")).isEqualTo(60);
		String token = verify(JsonPath.read(sent.getContentAsString(), "$.challengeId"), this.sms.lastCode(e164), ip);

		MockHttpServletResponse registered = register(token, "Ngọc Hà", "ca phe sua da", null, ip);

		assertThat(registered.getStatus()).as(registered.getContentAsString()).isEqualTo(201);
		assertThat(registered.getHeader("Set-Cookie")).as("no session (AN4)").isNull();
		String customerId = JsonPath.read(registered.getContentAsString(), "$.customerId");
		String userId = this.jdbc.sql("""
				SELECT BIN_TO_UUID(p.user_id) FROM cus_customer_profile p WHERE p.id = UUID_TO_BIN(?)
				""").param(customerId).query(String.class).single();
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_login_identifier
				WHERE identifier_type = 'PHONE' AND normalized_value = ? AND verified_at IS NOT NULL
				  AND user_id = UUID_TO_BIN(?)
				""").params(e164, userId).query(Integer.class).single()).as("phone stored as E.164, verified").isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT must_change_password FROM iam_user_credential WHERE user_id = UUID_TO_BIN(?)")
			.param(userId).query(Boolean.class).single()).isFalse();
		assertThat(events("OTP_REQUESTED", ip)).isEqualTo(1);
		assertThat(events("OTP_VERIFIED", ip)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT metadata FROM iam_security_event WHERE event_type = 'OTP_REQUESTED' AND ip_address = ?")
			.param(ip).query(String.class).single()).as("target masked").doesNotContain(e164).contains("+849");

		assertProblem(register(token, "Lại", "ca phe sua da", null, ip), 401, "VERIFICATION_TOKEN_INVALID");
		this.clock.advance(Duration.ofSeconds(61));
		String again = otpToken(national, freshIp());
		assertProblem(register(again, "Lại", "ca phe sua da", null, ip), 409, "CUSTOMER_ALREADY_REGISTERED");
	}

	@Test
	void theCustomerPasswordPolicyApplies() throws Exception {
		String ip = freshIp();
		String token = otpToken(freshNationalPhone(), ip);

		assertProblem(register(token, "Hà", "1234567", null, ip), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(register(token, "Hà", "12345678", null, ip), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(register(token, "Hà", "matkhau123", null, ip), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(register(token, "Hà", null, null, ip), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(register(token, "   ", "ca phe sua da", null, ip), 400, "VALIDATION_FAILED");
		assertThat(register(token, "Hà", "ca phe sua da", null, ip).getStatus()).as("token still usable after refusals")
			.isEqualTo(201);
	}

	// --- phone of an existing User (AN4, Identity §2.11) -------------------------------------------------------

	@Test
	void aStaffPhoneRegistersWithItsCurrentPasswordAndNoSecondCredential() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		String national = freshNationalPhone();
		addPhone(staff, "+84" + national.substring(1));
		String ip = freshIp();
		String token = otpToken(national, ip);

		assertProblem(register(token, "Minh", "ca phe sua da", null, ip), 422, "AUTH_CURRENT_PASSWORD_REQUIRED");
		MockHttpServletResponse registered = register(token, "Minh", null, IdentityFixtures.PASSWORD, ip);

		assertThat(registered.getStatus()).as(registered.getContentAsString()).isEqualTo(201);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM cus_customer_profile WHERE user_id = ?")
			.param(UuidBinary.toBytes(staff)).query(Integer.class).single()).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_user_credential WHERE user_id = ?")
			.param(UuidBinary.toBytes(staff)).query(Integer.class).single()).as("same credential").isEqualTo(1);
	}

	@Test
	void aWrongCurrentPasswordSpendsTheProof() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		String national = freshNationalPhone();
		addPhone(staff, "+84" + national.substring(1));
		String ip = freshIp();
		String token = otpToken(national, ip);

		assertProblem(register(token, "Minh", null, "not the password", ip), 401, "AUTH_INVALID_CREDENTIALS");
		assertProblem(register(token, "Minh", null, IdentityFixtures.PASSWORD, ip), 401, "VERIFICATION_TOKEN_INVALID");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM cus_customer_profile WHERE user_id = ?")
			.param(UuidBinary.toBytes(staff)).query(Integer.class).single()).isZero();
	}

	// --- OTP rules (AN1–AN3) -----------------------------------------------------------------------------------

	@Test
	void fiveWrongCodesLockTheChallenge() throws Exception {
		String national = freshNationalPhone();
		String ip = freshIp();
		String challenge = JsonPath.read(requestOtp(national, ip).getContentAsString(), "$.challengeId");
		String right = this.sms.lastCode("+84" + national.substring(1));
		String wrong = right.equals("000000") ? "111111" : "000000";

		for (int i = 0; i < 4; i++) {
			assertProblem(verifyResponse(challenge, wrong, ip), 401, "OTP_CODE_INVALID");
		}
		assertProblem(verifyResponse(challenge, wrong, ip), 401, "OTP_CHALLENGE_LOCKED");
		assertProblem(verifyResponse(challenge, right, ip), 401, "OTP_CHALLENGE_INVALID");
		assertThat(events("OTP_FAILED", ip)).isEqualTo(5);
		assertThat(this.jdbc.sql("SELECT status FROM iam_verification_challenge WHERE id = UUID_TO_BIN(?)")
			.param(challenge).query(String.class).single()).isEqualTo("FAILED");
	}

	@Test
	void aNewCodeEndsTheOldOneAndCodesExpire() throws Exception {
		String national = freshNationalPhone();
		String e164 = "+84" + national.substring(1);
		String ip = freshIp();
		String first = JsonPath.read(requestOtp(national, ip).getContentAsString(), "$.challengeId");
		String firstCode = this.sms.lastCode(e164);
		this.clock.advance(Duration.ofSeconds(61));
		String second = JsonPath.read(requestOtp(national, ip).getContentAsString(), "$.challengeId");

		assertProblem(verifyResponse(first, firstCode, ip), 401, "OTP_CHALLENGE_INVALID");
		this.clock.advance(Duration.ofMinutes(5));
		assertProblem(verifyResponse(second, this.sms.lastCode(e164), ip), 401, "OTP_CHALLENGE_INVALID");
	}

	@Test
	void theVerificationTokenExpiresAfterTenMinutes() throws Exception {
		String ip = freshIp();
		String token = otpToken(freshNationalPhone(), ip);
		this.clock.advance(Duration.ofMinutes(10));

		assertProblem(register(token, "Hà", "ca phe sua da", null, ip), 401, "VERIFICATION_TOKEN_INVALID");
		assertProblem(register("not-a-token", "Hà", "ca phe sua da", null, ip), 401, "VERIFICATION_TOKEN_INVALID");
	}

	@Test
	void onlyVietnamesePhonesAreAccepted() throws Exception {
		assertProblem(requestOtp("+14155550123", freshIp()), 400, "VALIDATION_FAILED");
		assertProblem(requestOtp("012345", freshIp()), 400, "VALIDATION_FAILED");
		assertThat(requestOtp("84" + freshNationalPhone().substring(1), freshIp()).getStatus()).isEqualTo(202);
	}

	// --- limits (AN5) ------------------------------------------------------------------------------------------

	@Test
	void codesPerPhoneAreLimitedPerMinuteAndPerHour() throws Exception {
		this.clock.freeze();
		String national = freshNationalPhone();
		assertThat(requestOtp(national, freshIp()).getStatus()).isEqualTo(202);
		this.clock.advance(Duration.ofSeconds(20));
		MockHttpServletResponse tooSoon = requestOtp(national, freshIp());
		assertProblem(tooSoon, 429, "RATE_LIMITED");
		assertThat(tooSoon.getHeader("Retry-After")).isEqualTo("40");

		for (int i = 0; i < 4; i++) {
			this.clock.advance(Duration.ofSeconds(61));
			assertThat(requestOtp(national, freshIp()).getStatus()).isEqualTo(202);
		}
		this.clock.advance(Duration.ofSeconds(61));
		assertProblem(requestOtp(national, freshIp()), 429, "RATE_LIMITED");
	}

	@Test
	void codesPerIpAreLimitedPerHour() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 20; i++) {
			assertThat(requestOtp(freshNationalPhone(), ip).getStatus()).isEqualTo(202);
		}

		assertProblem(requestOtp(freshNationalPhone(), ip), 429, "RATE_LIMITED");
		assertThat(events("RATE_LIMIT_EXCEEDED", ip)).isEqualTo(1);
	}

	// --- one transaction (Identity §2.11, Customer §3.5) -------------------------------------------------------

	@Test
	void aFailureCreatingTheProfileLeavesNoUserBehind() throws Exception {
		String national = freshNationalPhone();
		String ip = freshIp();
		String token = otpToken(national, ip);
		// Stub the spy behind the transactional proxy: calling the proxy would start a MANDATORY transaction.
		CustomerProfiles spy = AopTestUtils.getUltimateTargetObject(this.profiles);
		doThrow(new IllegalStateException("profile store down")).when(spy).create(any(), anyString());

		assertThat(register(token, "Hà", "ca phe sua da", null, ip).getStatus()).isEqualTo(500);

		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_login_identifier WHERE normalized_value = ?")
			.param("+84" + national.substring(1)).query(Integer.class).single()).isZero();
	}

	// --- review of PR #101 ------------------------------------------------------------------------------------

	@Test
	void twoRegistrationsOfOneNewPhoneAtOnceEndInAConflictNotAnError() throws Exception {
		String national = freshNationalPhone();
		String e164 = "+84" + national.substring(1);
		String ip = freshIp();
		assertThat(register(otpToken(national, ip), "Hà", "ca phe sua da", null, ip).getStatus()).isEqualTo(201);
		this.clock.advance(Duration.ofSeconds(61));
		String second = otpToken(national, freshIp());
		// The second request looked the phone up before the first one committed.
		doReturn(Optional.empty()).when(this.identifiers).findByTypeAndNormalizedValue(IdentifierType.PHONE, e164);

		assertProblem(register(second, "Hà", "ca phe sua da", null, ip), 409, "CUSTOMER_ALREADY_REGISTERED");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_login_identifier WHERE normalized_value = ?")
			.param(e164).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void anUnverifiedStaffPhoneIsVerifiedByTheOtp() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		String national = freshNationalPhone();
		addPhone(staff, "+84" + national.substring(1));
		this.jdbc.sql("UPDATE iam_login_identifier SET verified_at = NULL WHERE normalized_value = ?")
			.param("+84" + national.substring(1)).update();
		String ip = freshIp();

		assertThat(register(otpToken(national, ip), "Minh", null, IdentityFixtures.PASSWORD, ip).getStatus())
			.isEqualTo(201);

		assertThat(this.jdbc.sql("SELECT verified_at IS NOT NULL FROM iam_login_identifier WHERE normalized_value = ?")
			.param("+84" + national.substring(1)).query(Boolean.class).single()).isTrue();
	}

	@Test
	void aDisabledUserCannotAddACustomerProfile() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		String national = freshNationalPhone();
		addPhone(staff, "+84" + national.substring(1));
		this.jdbc.sql("UPDATE iam_user SET status = 'DISABLED' WHERE id = ?").param(UuidBinary.toBytes(staff)).update();
		String ip = freshIp();

		assertProblem(register(otpToken(national, ip), "Minh", null, IdentityFixtures.PASSWORD, ip), 401,
				"AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void anMfaChallengeCannotBeAnsweredAsAnOtp() throws Exception {
		UUID challenge = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO iam_verification_challenge (id, purpose, target, channel, status, attempt_count, max_attempts,
				  expires_at, created_at, version)
				VALUES (?, 'MFA_LOGIN', 'someone@hayi.test', 'TOTP', 'PENDING', 0, 5, ?, ?, 0)
				""").params(UuidBinary.toBytes(challenge), now.plusSeconds(300), now).update();

		assertProblem(verifyResponse(challenge.toString(), "123456", freshIp()), 401, "OTP_CHALLENGE_INVALID");
	}

	@Test
	void aTokenForAnotherPurposeIsNotAProof() throws Exception {
		String otherAudience = this.tokens.issuePasswordChangeToken(UuidV7.generate());

		assertProblem(register(otherAudience, "Hà", "ca phe sua da", null, freshIp()), 401,
				"VERIFICATION_TOKEN_INVALID");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/** A fresh {@code 09xxxxxxxx}. */
	private static String freshNationalPhone() {
		long n = (Instant.now().toEpochMilli() * 100 + NEXT.getAndIncrement()) % 100_000_000L;
		return "09" + String.format("%08d", n);
	}

	private static String freshIp() {
		int n = NEXT.getAndIncrement();
		return "203.0." + (n / 250 % 250) + "." + (n % 250 + 1);
	}

	private MockHttpServletResponse requestOtp(String phone, String ip) throws Exception {
		return this.mvc.perform(post(AUTH + "/otp").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"purpose\": \"REGISTRATION\"}".formatted(phone))).andReturn().getResponse();
	}

	private MockHttpServletResponse verifyResponse(String challengeId, String code, String ip) throws Exception {
		return this.mvc.perform(post(AUTH + "/otp/verify").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
			.content("{\"challengeId\": \"%s\", \"code\": \"%s\"}".formatted(challengeId, code))).andReturn()
			.getResponse();
	}

	private String verify(String challengeId, String code, String ip) throws Exception {
		MockHttpServletResponse verified = verifyResponse(challengeId, code, ip);
		assertThat(verified.getStatus()).as(verified.getContentAsString()).isEqualTo(200);
		return JsonPath.read(verified.getContentAsString(), "$.verificationToken");
	}

	private String otpToken(String nationalPhone, String ip) throws Exception {
		MockHttpServletResponse sent = requestOtp(nationalPhone, ip);
		assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(202);
		return verify(JsonPath.read(sent.getContentAsString(), "$.challengeId"),
				this.sms.lastCode("+84" + nationalPhone.substring(1)), ip);
	}

	private MockHttpServletResponse register(String token, String displayName, String password,
			String currentPassword, String ip) throws Exception {
		String body = "{\"verificationToken\": \"%s\", \"displayName\": \"%s\"%s%s}".formatted(token, displayName,
				(password != null) ? ", \"password\": \"" + password + "\"" : "",
				(currentPassword != null) ? ", \"currentPassword\": \"" + currentPassword + "\"" : "");
		return this.mvc.perform(post(AUTH + "/register").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
	}

	private void addPhone(UUID userId, String e164) {
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), e164, now, now, now)
			.update();
	}

	private int events(String type, String ip) {
		return this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = ? AND ip_address = ?")
			.params(type, ip).query(Integer.class).single();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
