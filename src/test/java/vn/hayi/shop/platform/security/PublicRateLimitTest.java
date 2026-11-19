package vn.hayi.shop.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;

/**
 * {@code /api/public/v1} is limited per IP (Decision W9, API 01-security §6.3), with small limits so the test stays
 * short.
 */
@SpringBootTest(properties = { "hayi.rate-limit.public.get-per-minute=3",
		"hayi.rate-limit.public.resolve-per-minute=2", "hayi.rate-limit.public.trusted-proxies=10.9.0.1" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class })
class PublicRateLimitTest {

	private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void getsOverTheLimitAreRefusedWithRetryAfterAndOneSecurityEvent() throws Exception {
		this.clock.freeze();
		String ip = freshIp();
		for (int i = 0; i < 3; i++) {
			assertThat(branches(ip).getStatus()).isEqualTo(200);
		}

		MockHttpServletResponse refused = branches(ip);

		assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(429);
		assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
		assertThat(branches(ip).getStatus()).isEqualTo(429);
		assertThat(events(ip)).as("one event per window, not per request").isEqualTo(1);
		assertThat(branches(freshIp()).getStatus()).as("another IP keeps its budget").isEqualTo(200);
	}

	@Test
	void aNewWindowStartsAfterAMinute() throws Exception {
		this.clock.freeze();
		String ip = freshIp();
		for (int i = 0; i < 3; i++) {
			branches(ip);
		}
		this.clock.advance(Duration.ofSeconds(45));
		MockHttpServletResponse refused = branches(ip);
		assertThat(refused.getStatus()).isEqualTo(429);
		assertThat(refused.getHeader("Retry-After")).isEqualTo("15");

		this.clock.advance(Duration.ofSeconds(15));

		assertThat(branches(ip).getStatus()).isEqualTo(200);
	}

	@Test
	void resolveHasItsOwnSmallerBudget() throws Exception {
		this.clock.freeze();
		String ip = freshIp();
		assertThat(resolve(ip).getStatus()).isNotEqualTo(429);
		assertThat(resolve(ip).getStatus()).isNotEqualTo(429);

		assertThat(resolve(ip).getStatus()).isEqualTo(429);
		assertThat(branches(ip).getStatus()).as("GET budget untouched").isEqualTo(200);
	}

	@Test
	void forwardedForCountsOnlyBehindATrustedProxy() throws Exception {
		this.clock.freeze();
		String spoofed = freshIp();
		String untrustedPeer = freshIp();
		for (int i = 0; i < 3; i++) {
			assertThat(this.mvc.perform(from(get("/api/public/v1/branches"), untrustedPeer)
				.header("X-Forwarded-For", freshIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
		}
		assertThat(this.mvc.perform(from(get("/api/public/v1/branches"), untrustedPeer)
			.header("X-Forwarded-For", freshIp())).andReturn().getResponse().getStatus())
			.as("a header from an untrusted peer is ignored: the peer is counted")
			.isEqualTo(429);

		for (int i = 0; i < 3; i++) {
			assertThat(behindProxy(spoofed).getStatus()).isEqualTo(200);
		}
		assertThat(behindProxy(spoofed).getStatus()).as("the client behind the proxy is counted").isEqualTo(429);
		assertThat(behindProxy(freshIp()).getStatus()).as("the proxy itself is not one client").isEqualTo(200);
	}

	@Test
	void backofficeIsNotLimitedHere() throws Exception {
		this.clock.freeze();
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			assertThat(this.mvc.perform(from(get("/api/backoffice/v1/auth/csrf"), ip)).andReturn().getResponse()
				.getStatus()).isNotEqualTo(429);
		}
	}

	private static String freshIp() {
		int n = NEXT_IP.getAndIncrement();
		return "198.51." + (n / 250) + "." + (n % 250 + 1);
	}

	private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
		return request.with(r -> {
			r.setRemoteAddr(ip);
			return r;
		});
	}

	private MockHttpServletResponse branches(String ip) throws Exception {
		return this.mvc.perform(from(get("/api/public/v1/branches"), ip)).andReturn().getResponse();
	}

	private MockHttpServletResponse behindProxy(String client) throws Exception {
		return this.mvc.perform(from(get("/api/public/v1/branches"), "10.9.0.1")
			.header("X-Forwarded-For", "203.0.113.7, " + client)).andReturn().getResponse();
	}

	private MockHttpServletResponse resolve(String ip) throws Exception {
		return this.mvc.perform(from(post("/api/public/v1/branches/" + UUID.randomUUID() + "/catalog/resolve"), ip)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\": []}")).andReturn().getResponse();
	}

	private int events(String ip) {
		return this.jdbc.sql("SELECT COUNT(*) FROM iam_security_event WHERE event_type = 'RATE_LIMIT_EXCEEDED' "
				+ "AND ip_address = ?").param(ip).query(Integer.class).single();
	}

}
