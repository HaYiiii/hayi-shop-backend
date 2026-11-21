package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;

/**
 * Login per IP and refresh per session are limited on both surfaces (API 01-security §6.3, Decision K5, AO4, BE #22),
 * with small limits so the test stays short.
 */
@SpringBootTest(properties = { "hayi.rate-limit.auth.login-per-minute=3", "hayi.rate-limit.auth.refresh-per-minute=2" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class AuthRateLimitTest {

	private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdentityFixtures fixtures;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void theFourthLoginOfAMinuteFromOneIpIsRefusedPerSurface() throws Exception {
		this.clock.freeze();
		String ip = freshIp();
		for (int i = 0; i < 3; i++) {
			assertThat(customerLogin(ip).getStatus()).isEqualTo(401);
		}

		MockHttpServletResponse refused = customerLogin(ip);

		assertProblem(refused, 429, "RATE_LIMITED");
		assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
		assertThat(customerLogin(ip).getStatus()).isEqualTo(429);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = 'RATE_LIMIT_EXCEEDED' "
				+ "AND ip_address = ?").param(ip).query(Integer.class).single()).as("one event per window").isEqualTo(1);
		assertThat(backofficeLogin(ip).getStatus()).as("the Backoffice counts on its own").isEqualTo(401);
		assertThat(customerLogin(freshIp()).getStatus()).as("another IP").isEqualTo(401);

		this.clock.advance(Duration.ofSeconds(60));
		assertThat(customerLogin(ip).getStatus()).as("a new window").isEqualTo(401);
	}

	@Test
	void theThirdRefreshOfAMinuteOfOneSessionIsRefused() throws Exception {
		String email = IdentityFixtures.uniqueEmail();
		this.fixtures.storeAdmin(email);
		BackofficeAuth auth = new BackofficeAuth(this.mvc, this.clock);
		Session session = auth.loginSession(email, IdentityFixtures.PASSWORD);
		this.clock.freeze();

		for (int i = 0; i < 2; i++) {
			MockHttpServletResponse rotated = auth.refresh(session);
			assertThat(rotated.getStatus()).as(rotated.getContentAsString()).isEqualTo(204);
			session = session.update(rotated);
		}

		MockHttpServletResponse refused = auth.refresh(session);
		assertProblem(refused, 429, "RATE_LIMITED");
		assertThat(refused.getHeader("Retry-After")).isNotNull();
	}

	private static String freshIp() {
		int n = NEXT_IP.getAndIncrement();
		return "192.0.2." + (n % 250 + 1);
	}

	private MockHttpServletResponse customerLogin(String ip) throws Exception {
		return this.mvc.perform(post("/api/customer/v1/auth/login").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phone\": \"0900000001\", \"password\": \"nope nope\"}"))
			.andReturn()
			.getResponse();
	}

	private MockHttpServletResponse backofficeLogin(String ip) throws Exception {
		return this.mvc.perform(post("/api/backoffice/v1/auth/login").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
			.content("{\"identifier\": \"nobody@hayi.test\", \"password\": \"nope nope\"}"))
			.andReturn()
			.getResponse();
	}

	private static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
