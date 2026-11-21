package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A Customer logs in with phone and password on the Customer surface (Identity §2.5–2.6, API 01-security §3–§6.1,
 * Decision AO): cookies scoped to the surface, refresh rotation and reuse, logout, {@code /me}, and the persona split
 * with the Backoffice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class CustomerLoginTest {

	static final String AUTH = "/api/customer/v1/auth";

	static final String PASSWORD = "ca phe sua da";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	IdentityFixtures fixtures;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void aCustomerLogsInWithCookiesScopedToTheCustomerSurface() throws Exception {
		Customer customer = customer("Ngọc Hà");

		MockHttpServletResponse login = login(customer.national(), PASSWORD);

		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(login.getContentAsString(), "$.verificationRequired")).isFalse();
		assertThat(setCookie(login, "hy_c_at")).contains("Path=/api/customer;").contains("HttpOnly")
			.contains("SameSite=Lax");
		assertThat(setCookie(login, "hy_c_rt")).contains("Path=/api/customer/v1/auth").contains("SameSite=Strict");
		assertThat(login.getCookie("hy_b_at")).as("no Backoffice cookie").isNull();
		Jar jar = Jar.from(login);

		MockHttpServletResponse me = perform(get(AUTH + "/me"), jar, false);
		assertThat(me.getStatus()).as(me.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<String>read(me.getContentAsString(), "$.customerId")).isEqualTo(customer.customerId());
		assertThat(JsonPath.<String>read(me.getContentAsString(), "$.displayName")).isEqualTo("Ngọc Hà");
		assertThat(JsonPath.<String>read(me.getContentAsString(), "$.phoneMasked"))
			.startsWith(customer.e164().substring(0, 5)).endsWith(customer.e164().substring(customer.e164().length() - 3)).contains("*");
		assertThat(this.jdbc.sql("""
				SELECT persona, channel FROM iam_user_session WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
				""").param(UuidBinary.toBytes(customer.userId())).query().singleRow())
			.containsEntry("persona", "CUSTOMER").containsEntry("channel", "CUSTOMER_WEB");
	}

	@Test
	void wrongCredentialsAndAnAccountWithoutProfileAreAllInvalidCredentials() throws Exception {
		Customer customer = customer("Hà");
		UUID staff = this.fixtures.branchStaff();
		String staffNational = freshNational();
		addPhone(staff, "+84" + staffNational.substring(1));

		assertProblem(login(customer.national(), "wrong password"), 401, "AUTH_INVALID_CREDENTIALS");
		assertProblem(login(freshNational(), PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
		assertProblem(login("not a phone", PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
		assertProblem(login(staffNational, IdentityFixtures.PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM iam_security_event WHERE user_id = ? AND event_type = 'LOGIN_FAILED'
				  AND JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.reason')) = 'NO_CUSTOMER_PROFILE'
				""").param(UuidBinary.toBytes(staff)).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void fiveWrongPasswordsLockTheAccountOnBothSurfaces() throws Exception {
		Customer customer = customer("Hà");
		for (int i = 0; i < 4; i++) {
			assertProblem(login(customer.national(), "wrong password"), 401, "AUTH_INVALID_CREDENTIALS");
		}
		MockHttpServletResponse locked = login(customer.national(), "wrong password");
		assertProblem(locked, 429, "AUTH_LOGIN_LOCKED");
		assertThat(locked.getHeader("Retry-After")).isNotNull();
		assertProblem(login(customer.national(), PASSWORD), 429, "AUTH_LOGIN_LOCKED");
	}

	@Test
	void refreshRotatesAndAReusedTokenRevokesTheSession() throws Exception {
		Jar jar = Jar.from(login(customer("Hà").national(), PASSWORD));

		MockHttpServletResponse rotated = perform(post(AUTH + "/refresh"), jar, true);
		assertThat(rotated.getStatus()).as(rotated.getContentAsString()).isEqualTo(204);
		Jar next = jar.update(rotated);
		assertThat(next.refresh()).isNotEqualTo(jar.refresh());

		this.clock.advance(Duration.ofSeconds(11));
		assertProblem(perform(post(AUTH + "/refresh"), jar.withCsrf(next.csrf()), true), 401, "AUTH_SESSION_REVOKED");
		assertThat(perform(get(AUTH + "/me"), next, false).getStatus()).as("whole session revoked").isEqualTo(401);
	}

	@Test
	void logoutRevokesTheSessionAndClearsTheCookies() throws Exception {
		Jar jar = Jar.from(login(customer("Hà").national(), PASSWORD));

		MockHttpServletResponse out = perform(post(AUTH + "/logout"), jar, true);

		assertThat(out.getStatus()).as(out.getContentAsString()).isEqualTo(204);
		assertThat(setCookie(out, "hy_c_at")).contains("Max-Age=0");
		assertThat(perform(get(AUTH + "/me"), jar, false).getStatus()).isEqualTo(401);
		assertThat(perform(get(AUTH + "/csrf"), jar, false).getStatus()).isEqualTo(401);
	}

	@Test
	void eachSurfaceRefusesTheOtherPersonasTokens() throws Exception {
		Customer customer = customer("Hà");
		Jar customerJar = Jar.from(login(customer.national(), PASSWORD));
		String email = IdentityFixtures.uniqueEmail();
		this.fixtures.storeAdmin(email);
		Session staff = new BackofficeAuth(this.mvc, this.clock).loginSession(email, IdentityFixtures.PASSWORD);

		assertThat(this.mvc.perform(get("/api/backoffice/v1/auth/me").cookie(new Cookie("hy_b_at", customerJar.access())))
			.andReturn().getResponse().getStatus()).as("Customer token on the Backoffice").isEqualTo(401);
		assertThat(this.mvc.perform(get(AUTH + "/me").cookie(new Cookie("hy_c_at", staff.access())))
			.andReturn().getResponse().getStatus()).as("Backoffice token on the Customer surface").isEqualTo(401);
		assertProblem(this.mvc.perform(post(AUTH + "/refresh").cookie(new Cookie("hy_c_rt", staff.refresh()),
				new Cookie("hy_csrf", staff.csrf())).header("X-CSRF-Token", staff.csrf())).andReturn().getResponse(),
				401, "AUTH_REQUIRED");
	}

	@Test
	void aUserWithBothPersonasHasTwoIndependentSessions() throws Exception {
		UUID staffUser = this.fixtures.branchStaff();
		String national = freshNational();
		addPhone(staffUser, "+84" + national.substring(1));
		addProfile(staffUser, "Minh");
		Session backoffice = this.fixtures.openSession(staffUser);
		Jar customerJar = Jar.from(login(national, IdentityFixtures.PASSWORD));

		assertThat(perform(post(AUTH + "/logout"), customerJar, true).getStatus()).isEqualTo(204);

		assertThat(this.mvc.perform(get("/api/backoffice/v1/branches").cookie(new Cookie("hy_b_at", backoffice.access())))
			.andReturn().getResponse().getStatus()).as("Backoffice session untouched").isNotEqualTo(401);
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	record Customer(UUID userId, String customerId, String national, String e164) {
	}

	/** Cookie values the browser holds for the Customer surface. */
	record Jar(String access, String refresh, String csrf) {

		static Jar from(MockHttpServletResponse response) {
			assertThat(response.getCookie("hy_c_at")).as("logged in").isNotNull();
			return new Jar(value(response, "hy_c_at"), value(response, "hy_c_rt"), value(response, "hy_csrf"));
		}

		Jar update(MockHttpServletResponse response) {
			return new Jar(orElse(value(response, "hy_c_at"), this.access), orElse(value(response, "hy_c_rt"), this.refresh),
					orElse(value(response, "hy_csrf"), this.csrf));
		}

		Jar withCsrf(String csrf) {
			return new Jar(this.access, this.refresh, csrf);
		}

		private static String orElse(String value, String fallback) {
			return (value != null) ? value : fallback;
		}

		private static String value(MockHttpServletResponse response, String name) {
			Cookie cookie = response.getCookie(name);
			return (cookie != null) ? cookie.getValue() : null;
		}

	}

	private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, Jar jar, boolean csrfHeader)
			throws Exception {
		request.cookie(new Cookie("hy_c_at", jar.access()), new Cookie("hy_c_rt", jar.refresh()),
				new Cookie("hy_csrf", jar.csrf()));
		if (csrfHeader) {
			request.header("X-CSRF-Token", jar.csrf());
		}
		return this.mvc.perform(request).andReturn().getResponse();
	}

	private MockHttpServletResponse login(String phone, String password) throws Exception {
		return this.mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(phone, password))).andReturn().getResponse();
	}

	private static String setCookie(MockHttpServletResponse response, String name) {
		return response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith(name + "=")).findFirst()
			.orElseThrow(() -> new AssertionError("no Set-Cookie " + name));
	}

	private static String freshNational() {
		long n = (Instant.now().toEpochMilli() * 100 + NEXT.getAndIncrement()) % 100_000_000L;
		return "08" + String.format("%08d", n);
	}

	/** A registered Customer: User, verified phone, chosen password and profile. */
	private Customer customer(String displayName) {
		String national = freshNational();
		String e164 = "+84" + national.substring(1);
		UUID userId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(UuidBinary.toBytes(userId), now, now).update();
		addPhone(userId, e164);
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, 0, ?, ?)
				""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode(PASSWORD), now, now).update();
		UUID customerId = addProfile(userId, displayName);
		return new Customer(userId, customerId.toString(), national, e164);
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

	private UUID addProfile(UUID userId, String displayName) {
		UUID customerId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("""
				INSERT INTO cus_customer_profile (id, user_id, display_name, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 0)
				""").params(UuidBinary.toBytes(customerId), UuidBinary.toBytes(userId), displayName, now, now).update();
		return customerId;
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
