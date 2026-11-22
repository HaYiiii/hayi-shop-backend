package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A Customer resets a forgotten password by OTP and changes it while signed in (Identity §2.3–2.4, API 01-security
 * §6.1, Decision AS): proofs, the shared credential ending sessions on both personas, and the per-user policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class,
		CapturingOtpSender.Config.class })
class CustomerPasswordTest {

	static final String AUTH = "/api/customer/v1/auth";

	static final String PASSWORD = "ca phe sua da";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	CapturingOtpSender sms;

	// --- reset (AS1, AS2) --------------------------------------------------------------------------------------

	@Test
	void aForgottenPasswordIsResetByOtpAndEverySessionEnds() throws Exception {
		Customer customer = customer(null);
		Jar old = login(customer.national(), PASSWORD);
		String token = recoveryToken(customer);

		MockHttpServletResponse reset = reset(token, "tra sua tran chau");

		assertThat(reset.getStatus()).as(reset.getContentAsString()).isEqualTo(204);
		assertThat(me(old).getStatus()).as("old session ended").isEqualTo(401);
		assertThat(loginResponse(customer.national(), PASSWORD).getStatus()).isEqualTo(401);
		assertThat(loginResponse(customer.national(), "tra sua tran chau").getStatus()).isEqualTo(200);
		assertProblem(reset(token, "mot mat khau khac"), 401, "VERIFICATION_TOKEN_INVALID");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = 'PASSWORD_RESET' "
				+ "AND user_id = ?").param(UuidBinary.toBytes(customer.userId())).query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void aRegistrationProofCannotResetAPassword() throws Exception {
		String national = freshNational();
		MockHttpServletResponse sent = requestOtp(national, "REGISTRATION");
		String token = verify(JsonPath.read(sent.getContentAsString(), "$.challengeId"),
				this.sms.lastCode("+84" + national.substring(1)));

		assertProblem(reset(token, "tra sua tran chau"), 401, "VERIFICATION_TOKEN_INVALID");
	}

	@Test
	void aPhoneWithoutCustomerGetsTheSameAnswerButNoSms() throws Exception {
		String national = freshNational();

		MockHttpServletResponse sent = requestOtp(national, "PASSWORD_RECOVERY");

		assertThat(sent.getStatus()).isEqualTo(202);
		assertThat(JsonPath.<String>read(sent.getContentAsString(), "$.challengeId")).isNotBlank();
		assertThat(this.sms.lastCode("+84" + national.substring(1))).as("no SMS").isNull();
	}

	@Test
	void aStaffMemberKeepsTheInternalPolicyAndLosesTheBackofficeSessionToo() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		Customer customer = customer(staff);
		Session backoffice = this.fixtures.openSession(staff);
		String token = recoveryToken(customer);

		assertProblem(reset(token, "tra sua 8c"), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertThat(reset(token, "tra sua tran chau tuoi").getStatus()).isEqualTo(204);

		assertThat(this.mvc.perform(get("/api/backoffice/v1/auth/me").cookie(new Cookie("hy_b_at", backoffice.access())))
			.andReturn().getResponse().getStatus()).as("STAFF session ended too").isEqualTo(401);
	}

	// --- change (AS3) ------------------------------------------------------------------------------------------

	@Test
	void aSignedInCustomerChangesThePasswordAndOtherSessionsEnd() throws Exception {
		Customer customer = customer(null);
		Jar current = login(customer.national(), PASSWORD);
		Jar other = login(customer.national(), PASSWORD);

		assertProblem(change(current, PASSWORD, PASSWORD), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		MockHttpServletResponse changed = change(current, PASSWORD, "tra sua tran chau");

		assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(204);
		assertThat(me(current).getStatus()).as("this session stays").isEqualTo(200);
		assertThat(me(other).getStatus()).as("the other one ends").isEqualTo(401);
		assertThat(loginResponse(customer.national(), "tra sua tran chau").getStatus()).isEqualTo(200);
	}

	@Test
	void wrongCurrentPasswordsCountTowardTheLock() throws Exception {
		Customer customer = customer(null);
		Jar jar = login(customer.national(), PASSWORD);

		for (int i = 0; i < 4; i++) {
			assertProblem(change(jar, "not the password", "tra sua tran chau"), 401, "AUTH_INVALID_CREDENTIALS");
		}
		assertProblem(change(jar, "not the password", "tra sua tran chau"), 429, "AUTH_LOGIN_LOCKED");
		assertProblem(change(jar, PASSWORD, "tra sua tran chau"), 429, "AUTH_LOGIN_LOCKED");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Customer(UUID userId, String national, String e164) {
	}

	record Jar(String access, String csrf) {
	}

	private String recoveryToken(Customer customer) throws Exception {
		MockHttpServletResponse sent = requestOtp(customer.national(), "PASSWORD_RECOVERY");
		assertThat(sent.getStatus()).as(sent.getContentAsString()).isEqualTo(202);
		return verify(JsonPath.read(sent.getContentAsString(), "$.challengeId"), this.sms.lastCode(customer.e164()));
	}

	private MockHttpServletResponse requestOtp(String phone, String purpose) throws Exception {
		return this.mvc.perform(post(AUTH + "/otp").with(r -> {
			r.setRemoteAddr("198.18." + NEXT.get() % 250 + "." + (NEXT.getAndIncrement() % 250 + 1));
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"purpose\": \"%s\"}".formatted(phone, purpose))).andReturn().getResponse();
	}

	private String verify(String challengeId, String code) throws Exception {
		MockHttpServletResponse verified = this.mvc.perform(post(AUTH + "/otp/verify")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"challengeId\": \"%s\", \"code\": \"%s\"}".formatted(challengeId, code))).andReturn().getResponse();
		assertThat(verified.getStatus()).as(verified.getContentAsString()).isEqualTo(200);
		return JsonPath.read(verified.getContentAsString(), "$.verificationToken");
	}

	private MockHttpServletResponse reset(String token, String newPassword) throws Exception {
		return this.mvc.perform(post(AUTH + "/password/reset").contentType(MediaType.APPLICATION_JSON)
			.content("{\"verificationToken\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, newPassword)))
			.andReturn()
			.getResponse();
	}

	private MockHttpServletResponse change(Jar jar, String current, String next) throws Exception {
		return this.mvc.perform(post(AUTH + "/password/change").contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next))
			.cookie(new Cookie("hy_c_at", jar.access()), new Cookie("hy_c_csrf", jar.csrf()))
			.header("X-CSRF-Token", jar.csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse me(Jar jar) throws Exception {
		return this.mvc.perform(get(AUTH + "/me").cookie(new Cookie("hy_c_at", jar.access()))).andReturn().getResponse();
	}

	private MockHttpServletResponse loginResponse(String phone, String password) throws Exception {
		return this.mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(phone, password))).andReturn().getResponse();
	}

	private Jar login(String phone, String password) throws Exception {
		MockHttpServletResponse login = loginResponse(phone, password);
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		return new Jar(login.getCookie("hy_c_at").getValue(), login.getCookie("hy_c_csrf").getValue());
	}

	private static String freshNational() {
		return "05" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
	}

	/** A Customer with a phone and {@link #PASSWORD}; {@code existingUser} gets the phone and profile added. */
	private Customer customer(UUID existingUser) {
		String national = freshNational();
		String e164 = "+84" + national.substring(1);
		Instant now = Instant.now();
		UUID userId = existingUser;
		if (userId == null) {
			userId = UuidV7.generate();
			this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
				.params(UuidBinary.toBytes(userId), now, now).update();
			this.jdbc.sql("""
					INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at,
					  updated_at)
					VALUES (?, ?, 0, ?, ?)
					""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode(PASSWORD), now, now).update();
		}
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), e164, now, now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO cus_customer_profile (id, user_id, display_name, created_at, updated_at, version)
				VALUES (?, ?, 'Hà', ?, ?, 0)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), now, now).update();
		return new Customer(userId, national, e164);
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
