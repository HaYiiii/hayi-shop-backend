package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
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
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.platform.persistence.UuidBinary;
import vn.hayi.shop.shared.id.UuidV7;

/** A signed-in Customer reads and renames their own profile (API 07 §1, Customer §3.2, Decision O). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class CustomerProfileTest {

	static final String PROFILE = "/api/customer/v1/me/profile";

	static final String PASSWORD = "ca phe sua da";

	private static final AtomicInteger NEXT = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	String e164;

	String customerId;

	String access;

	String csrf;

	@BeforeEach
	void setUp() throws Exception {
		String national = "03" + String.format("%08d", (Instant.now().toEpochMilli() * 10 + NEXT.getAndIncrement())
				% 100_000_000L);
		this.e164 = "+84" + national.substring(1);
		this.customerId = customer(this.e164, "Ngọc Hà").toString();
		MockHttpServletResponse login = this.mvc.perform(post("/api/customer/v1/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"phone\": \"%s\", \"password\": \"%s\"}".formatted(national, PASSWORD))).andReturn().getResponse();
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		this.access = login.getCookie("hy_c_at").getValue();
		this.csrf = login.getCookie("hy_c_csrf").getValue();
	}

	@Test
	void theCustomerReadsTheirProfileWithAMaskedPhoneAndNoLoyaltyYet() throws Exception {
		MockHttpServletResponse profile = signedIn(get(PROFILE));

		assertThat(profile.getStatus()).as(profile.getContentAsString()).isEqualTo(200);
		assertThat(profile.getHeader("ETag")).isEqualTo("\"v0\"");
		String body = profile.getContentAsString();
		assertThat(JsonPath.<String>read(body, "$.customerId")).isEqualTo(this.customerId);
		assertThat(JsonPath.<String>read(body, "$.displayName")).isEqualTo("Ngọc Hà");
		assertThat(JsonPath.<String>read(body, "$.phoneMasked")).contains("*").doesNotContain(this.e164);
		assertThat(body).contains("\"loyalty\":null");
	}

	@Test
	void theCustomerRenamesTheirProfileAtTheCurrentVersion() throws Exception {
		MockHttpServletResponse renamed = rename("\"v0\"", "{\"displayName\": \"  Hà Ngọc  \"}");

		assertThat(renamed.getStatus()).as(renamed.getContentAsString()).isEqualTo(200);
		assertThat(renamed.getHeader("ETag")).isEqualTo("\"v1\"");
		assertThat(JsonPath.<String>read(renamed.getContentAsString(), "$.displayName")).isEqualTo("Hà Ngọc");
		assertThat(rename("\"v1\"", "{\"displayName\": \"Hà Ngọc\"}").getHeader("ETag")).as("same name keeps it")
			.isEqualTo("\"v1\"");
		assertProblem(rename("\"v0\"", "{\"displayName\": \"Khác\"}"), 412, "VERSION_CONFLICT");
		assertProblem(rename(null, "{\"displayName\": \"Khác\"}"), 428, "PRECONDITION_REQUIRED");
		assertProblem(rename("\"v1\"", "{\"displayName\": null}"), 400, "VALIDATION_FAILED");
		assertProblem(rename("\"v1\"", "{\"phone\": \"0900000000\"}"), 400, "VALIDATION_FAILED");
	}

	@Test
	void withoutASessionTheProfileIsRefused() throws Exception {
		assertThat(this.mvc.perform(get(PROFILE)).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	private MockHttpServletResponse rename(String ifMatch, String json) throws Exception {
		MockHttpServletRequestBuilder request = patch(PROFILE).contentType("application/merge-patch+json")
			.content(json)
			.header("X-CSRF-Token", this.csrf);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return signedIn(request);
	}

	private MockHttpServletResponse signedIn(MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc.perform(request.cookie(new Cookie("hy_c_at", this.access), new Cookie("hy_c_csrf", this.csrf)))
			.andReturn()
			.getResponse();
	}

	private UUID customer(String e164, String displayName) {
		UUID userId = UuidV7.generate();
		UUID customerId = UuidV7.generate();
		Instant now = Instant.now();
		this.jdbc.sql("INSERT INTO iam_user (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
			.params(UuidBinary.toBytes(userId), now, now).update();
		this.jdbc.sql("""
				INSERT INTO iam_login_identifier (id, user_id, identifier_type, normalized_value, verified_at, created_at,
				  updated_at)
				VALUES (?, ?, 'PHONE', ?, ?, ?, ?)
				""").params(UuidBinary.toBytes(UuidV7.generate()), UuidBinary.toBytes(userId), e164, now, now, now)
			.update();
		this.jdbc.sql("""
				INSERT INTO iam_user_credential (user_id, password_hash, must_change_password, password_changed_at, updated_at)
				VALUES (?, ?, 0, ?, ?)
				""").params(UuidBinary.toBytes(userId), this.passwordEncoder.encode(PASSWORD), now, now).update();
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
