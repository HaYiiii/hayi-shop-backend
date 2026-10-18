package vn.hayi.shop.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.identity.domain.Totp;

/**
 * Drives the Backoffice auth endpoints over HTTP like the browser does: cookies in, cookies out. The authenticator
 * app is simulated from the fixture's TOTP secret and the test clock.
 */
final class BackofficeAuth {

	static final String AUTH = "/api/backoffice/v1/auth";

	private final MockMvc mvc;

	private final MutableClock clock;

	BackofficeAuth(MockMvc mvc, MutableClock clock) {
		this.mvc = mvc;
		this.clock = clock;
	}

	MockHttpServletResponse login(String identifier, String password) throws Exception {
		return this.mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"identifier": "%s", "password": "%s"}
					""".formatted(identifier, password)))
			.andReturn().getResponse();
	}

	/**
	 * Logs in, answering the MFA challenge with the fixture's authenticator, and returns the browser's cookie jar.
	 * The clock first moves to the next TOTP step, since a step is accepted once per factor.
	 */
	Session loginSession(String identifier, String password) throws Exception {
		MockHttpServletResponse response = login(identifier, password);
		if (response.getStatus() == 200 && JsonPath.<Boolean>read(response.getContentAsString(), "$.mfaRequired")) {
			this.clock.advance(Duration.ofSeconds(30));
			response = verifyMfa(JsonPath.read(response.getContentAsString(), "$.mfaChallengeId"),
					totp(IdentityFixtures.TOTP_SECRET, this.clock.instant()));
		}
		if (response.getStatus() / 100 != 2 || response.getCookie("hy_b_at") == null) {
			throw new AssertionError("login failed: " + response.getStatus() + " " + response.getContentAsString());
		}
		return Session.from(response);
	}

	MockHttpServletResponse verifyMfa(String challengeId, String code) throws Exception {
		return postJson("/mfa/verify", """
				{"mfaChallengeId": "%s", "code": "%s"}
				""".formatted(challengeId, code));
	}

	MockHttpServletResponse startEnrollment(String enrollmentToken) throws Exception {
		return postJson("/mfa/enroll", """
				{"enrollmentToken": "%s"}
				""".formatted(enrollmentToken));
	}

	MockHttpServletResponse confirmEnrollment(String enrollmentToken, String code) throws Exception {
		return postJson("/mfa/enroll/confirm", """
				{"enrollmentToken": "%s", "code": "%s"}
				""".formatted(enrollmentToken, code));
	}

	/** Sends {@code code} if given, else {@code password} if given, else an empty body. */
	MockHttpServletResponse stepUp(Session session, String code, String password) throws Exception {
		String body = (code != null) ? """
				{"code": "%s"}
				""".formatted(code) : (password != null) ? """
				{"password": "%s"}
				""".formatted(password) : "{}";
		return perform(post(AUTH + "/step-up").contentType(MediaType.APPLICATION_JSON).content(body), session, true);
	}

	/** The code the authenticator app shows at {@code time} for a base32 secret. */
	static String totp(String base32Secret, Instant time) {
		return Totp.codeAt(base32Decode(base32Secret), time);
	}

	static byte[] base32Decode(String base32) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		byte[] out = new byte[base32.length() * 5 / 8];
		int buffer = 0;
		int bits = 0;
		int index = 0;
		for (char c : base32.toCharArray()) {
			buffer = (buffer << 5) | alphabet.indexOf(c);
			bits += 5;
			if (bits >= 8) {
				out[index++] = (byte) (buffer >> (bits - 8));
				bits -= 8;
			}
		}
		return out;
	}

	private MockHttpServletResponse postJson(String path, String json) throws Exception {
		return this.mvc.perform(post(AUTH + path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andReturn().getResponse();
	}

	MockHttpServletResponse changeFirstPassword(String changeToken, String newPassword) throws Exception {
		return this.mvc.perform(post(AUTH + "/password/first-change").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"changeToken": "%s", "newPassword": "%s"}
					""".formatted(changeToken, newPassword)))
			.andReturn().getResponse();
	}

	MockHttpServletResponse refresh(Session session) throws Exception {
		return perform(post(AUTH + "/refresh"), session, true);
	}

	MockHttpServletResponse logout(Session session) throws Exception {
		return perform(post(AUTH + "/logout"), session, true);
	}

	MockHttpServletResponse csrf(Session session) throws Exception {
		return perform(get(AUTH + "/csrf"), session, false);
	}

	MockHttpServletResponse perform(MockHttpServletRequestBuilder request, Session session, boolean withCsrfHeader)
			throws Exception {
		session.cookies().forEach(request::cookie);
		if (withCsrfHeader && session.csrf() != null) {
			request.header("X-CSRF-Token", session.csrf());
		}
		return this.mvc.perform(request).andReturn().getResponse();
	}

	static Optional<String> setCookieHeader(MockHttpServletResponse response, String name) {
		return response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith(name + "=")).findFirst();
	}

	/** Cookie values the browser holds for the Backoffice. */
	record Session(String access, String refresh, String csrf) {

		static Session from(MockHttpServletResponse response) {
			return new Session(value(response, "hy_b_at"), value(response, "hy_b_rt"), value(response, "hy_csrf"));
		}

		/** The jar after a response that may replace some cookies. */
		Session update(MockHttpServletResponse response) {
			return new Session(Optional.ofNullable(value(response, "hy_b_at")).orElse(this.access),
					Optional.ofNullable(value(response, "hy_b_rt")).orElse(this.refresh),
					Optional.ofNullable(value(response, "hy_csrf")).orElse(this.csrf));
		}

		Session withCsrf(String csrf) {
			return new Session(this.access, this.refresh, csrf);
		}

		Session withoutCsrf() {
			return withCsrf(null);
		}

		List<Cookie> cookies() {
			return Arrays.asList(cookie("hy_b_at", this.access), cookie("hy_b_rt", this.refresh), cookie("hy_csrf", this.csrf))
				.stream().filter(cookie -> cookie != null).toList();
		}

		private static Cookie cookie(String name, String value) {
			return (value != null) ? new Cookie(name, value) : null;
		}

		private static String value(MockHttpServletResponse response, String name) {
			Cookie cookie = response.getCookie(name);
			return (cookie != null) ? cookie.getValue() : null;
		}

	}

}
