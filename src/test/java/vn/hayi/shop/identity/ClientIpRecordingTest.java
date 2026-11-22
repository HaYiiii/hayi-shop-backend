package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.persistence.UuidBinary;

/**
 * Sessions, security events and audit record the client behind the web's same-origin proxy, resolved through the
 * trusted proxies like the per-IP limits (Decision AU3, W9).
 */
@SpringBootTest(properties = "hayi.rate-limit.public.trusted-proxies=" + ClientIpRecordingTest.WEB_PROXY)
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class ClientIpRecordingTest {

	static final String WEB_PROXY = "10.9.0.5";

	static final String CLIENT = "203.0.113.7";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	@Test
	void aLoginThroughTheWebProxyRecordsTheClient() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		String email = emailOf(userId);

		MockHttpServletResponse login = this.mvc.perform(via(WEB_PROXY, CLIENT, post("/api/backoffice/v1/auth/login"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\": \"%s\", \"password\": \"%s\"}".formatted(email, IdentityFixtures.PASSWORD)))
			.andReturn().getResponse();

		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		assertThat(sessionIp(userId)).isEqualTo(CLIENT);
		assertThat(eventIp("LOGIN_SUCCESS", userId)).isEqualTo(CLIENT);
	}

	@Test
	void anAuditedChangeAndARefusedCsrfThroughTheWebProxyRecordTheClient() throws Exception {
		UUID admin = this.fixtures.storeAdmin(IdentityFixtures.uniqueEmail());
		Session session = this.fixtures.openSession(admin);
		BackofficeAuth auth = new BackofficeAuth(this.mvc, null);

		MockHttpServletResponse created = auth.perform(via(WEB_PROXY, CLIENT, post("/api/backoffice/v1/categories"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\": \"ip-" + UUID.randomUUID().toString().substring(0, 8) + "\", \"name\": \"IP\"}"),
				session, true);
		MockHttpServletResponse refused = auth.perform(
				via(WEB_PROXY, CLIENT, delete("/api/backoffice/v1/auth/sessions/" + UUID.randomUUID())), session,
				false);

		assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
		assertThat(this.jdbc.sql("SELECT ip_address FROM aud_audit_log WHERE actor_user_id = ?")
			.param(UuidBinary.toBytes(admin)).query(String.class).single()).isEqualTo(CLIENT);
		assertThat(refused.getStatus()).isEqualTo(403);
		assertThat(eventIp("CSRF_VALIDATION_FAILED", admin)).as("resolved before Spring Security").isEqualTo(CLIENT);
	}

	@Test
	void aForwardedForSentStraightFromTheClientIsIgnored() throws Exception {
		UUID userId = this.fixtures.branchStaff();
		String email = emailOf(userId);
		String peer = "198.51.100.9";

		MockHttpServletResponse login = this.mvc.perform(via(peer, CLIENT, post("/api/backoffice/v1/auth/login"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\": \"%s\", \"password\": \"%s\"}".formatted(email, IdentityFixtures.PASSWORD)))
			.andReturn().getResponse();

		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		assertThat(sessionIp(userId)).isEqualTo(peer);
		assertThat(eventIp("LOGIN_SUCCESS", userId)).isEqualTo(peer);
	}

	private static MockHttpServletRequestBuilder via(String peer, String forwardedFor,
			MockHttpServletRequestBuilder request) {
		return request.header("X-Forwarded-For", forwardedFor).with(r -> {
			r.setRemoteAddr(peer);
			return r;
		});
	}

	/** A Branch staff member logs in without MFA (no {@code requiresMfa} role). */
	private String emailOf(UUID userId) {
		return this.jdbc.sql("SELECT normalized_value FROM iam_login_identifier WHERE user_id = ? AND identifier_type = 'EMAIL'")
			.param(UuidBinary.toBytes(userId)).query(String.class).single();
	}

	private String sessionIp(UUID userId) {
		return this.jdbc.sql("SELECT ip_address FROM iam_user_session WHERE user_id = ?")
			.param(UuidBinary.toBytes(userId)).query(String.class).single();
	}

	private String eventIp(String type, UUID userId) {
		return this.jdbc.sql("SELECT ip_address FROM iam_security_event WHERE event_type = ? AND user_id = ?")
			.params(type, UuidBinary.toBytes(userId)).query(String.class).single();
	}

}
