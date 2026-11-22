package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
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
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A user sees the devices signed in on each surface and signs another one out (Identity §2.5, API 01-security
 * §6.1–6.2, Decision AT).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class DeviceSessionTest {

	static final String CUSTOMER = "/api/customer/v1/auth";

	static final String BACKOFFICE = "/api/backoffice/v1/auth";

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

	record Jar(String access, String csrf) {
	}

	@Test
	void aCustomerListsTheirDevicesAndSignsAnotherOneOut() throws Exception {
		String national = customer(null);
		Jar other = login(national, "Phone 1");
		this.clock.advance(java.time.Duration.ofSeconds(1));
		Jar current = login(national, "Laptop 2");

		MockHttpServletResponse listed = customerGet(current, "/sessions");

		assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
		String body = listed.getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.items[*].userAgent")).containsExactly("Laptop 2", "Phone 1");
		assertThat(JsonPath.<List<Boolean>>read(body, "$.items[*].current")).containsExactly(true, false);
		String otherId = JsonPath.<List<String>>read(body, "$.items[?(@.current == false)].sessionId").getFirst();
		String currentId = JsonPath.<List<String>>read(body, "$.items[?(@.current == true)].sessionId").getFirst();

		assertThat(customerDelete(current, otherId).getStatus()).isEqualTo(204);

		assertThat(customerGet(other, "/me").getStatus()).as("signed out at once").isEqualTo(401);
		assertThat(customerGet(current, "/me").getStatus()).isEqualTo(200);
		assertProblem(customerDelete(current, currentId), 409, "SESSION_IS_CURRENT");
		assertProblem(customerDelete(current, otherId), 404, "SESSION_NOT_FOUND");
		assertThat(JsonPath.<List<String>>read(customerGet(current, "/sessions").getContentAsString(),
				"$.items[*].sessionId")).containsExactly(currentId);
	}

	@Test
	void anotherUsersOrAnotherPersonasSessionIsNotFound() throws Exception {
		UUID staff = this.fixtures.branchStaff();
		String national = customer(staff);
		Jar customerJar = login(national, "Phone");
		Session backoffice = this.fixtures.openSession(staff);
		Jar stranger = login(customer(null), "Other");
		String backofficeId = JsonPath.<List<String>>read(this.mvc.perform(get(BACKOFFICE + "/sessions")
			.cookie(new Cookie("hy_b_at", backoffice.access()))).andReturn().getResponse().getContentAsString(),
				"$.items[*].sessionId").getFirst();

		assertThat(JsonPath.<List<Object>>read(customerGet(customerJar, "/sessions").getContentAsString(),
				"$.items")).as("only the Customer persona").hasSize(1);
		assertProblem(customerDelete(customerJar, backofficeId), 404, "SESSION_NOT_FOUND");
		String strangerId = JsonPath.<List<String>>read(customerGet(stranger, "/sessions").getContentAsString(),
				"$.items[*].sessionId").getFirst();
		assertProblem(customerDelete(customerJar, strangerId), 404, "SESSION_NOT_FOUND");
		assertProblem(customerDelete(customerJar, UUID.randomUUID().toString()), 404, "SESSION_NOT_FOUND");
	}

	@Test
	void aBackofficeUserSignsAnotherDeviceOutWithASecurityEvent() throws Exception {
		UUID admin = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		Session other = this.fixtures.openSession(admin);
		Session current = this.fixtures.openSession(admin);
		BackofficeAuth auth = new BackofficeAuth(this.mvc, this.clock);
		MockHttpServletResponse listed = auth.perform(get(BACKOFFICE + "/sessions"), current, false);
		assertThat(JsonPath.<List<Object>>read(listed.getContentAsString(), "$.items")).hasSize(2);
		String otherId = JsonPath.<List<String>>read(listed.getContentAsString(),
				"$.items[?(@.current == false)].sessionId").getFirst();

		assertThat(auth.perform(delete(BACKOFFICE + "/sessions/" + otherId), current, true).getStatus()).isEqualTo(204);

		assertThat(auth.perform(get(BACKOFFICE + "/me"), other, false).getStatus()).isEqualTo(401);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = 'SESSION_REVOKED' "
				+ "AND session_id = UUID_TO_BIN(?)").param(otherId).query(Integer.class).single()).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT revoke_reason FROM iam_user_session WHERE id = UUID_TO_BIN(?)")
			.param(otherId).query(String.class).single()).isEqualTo("USER_REVOKED");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private MockHttpServletResponse customerGet(Jar jar, String path) throws Exception {
		return this.mvc.perform(get(CUSTOMER + path).cookie(new Cookie("hy_c_at", jar.access()))).andReturn()
			.getResponse();
	}

	private MockHttpServletResponse customerDelete(Jar jar, String sessionId) throws Exception {
		return this.mvc.perform(delete(CUSTOMER + "/sessions/" + sessionId)
			.cookie(new Cookie("hy_c_at", jar.access()), new Cookie("hy_c_csrf", jar.csrf()))
			.header("X-CSRF-Token", jar.csrf())).andReturn().getResponse();
	}

	private Jar login(String national, String userAgent) throws Exception {
		MockHttpServletResponse login = this.mvc.perform(post(CUSTOMER + "/login").header("User-Agent", userAgent)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(national, PASSWORD))).andReturn().getResponse();
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		return new Jar(login.getCookie("hy_c_at").getValue(), login.getCookie("hy_c_csrf").getValue());
	}

	/** A Customer with {@link #PASSWORD}, or the profile and phone added to {@code existingUser}. */
	private String customer(UUID existingUser) {
		String national = "04" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
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
		else {
			this.jdbc.sql("UPDATE iam_user_credential SET password_hash = ? WHERE user_id = ?")
				.params(this.passwordEncoder.encode(PASSWORD), UuidBinary.toBytes(userId)).update();
		}
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), "+84" + national.substring(1),
				now, now, now).update();
		this.jdbc.sql("""
				INSERT INTO cus_customer_profile (id, user_id, display_name, created_at, updated_at, version)
				VALUES (?, ?, 'Hà', ?, ?, 0)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), now, now).update();
		return national;
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
