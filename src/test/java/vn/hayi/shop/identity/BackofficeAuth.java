package vn.hayi.shop.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Drives the Backoffice auth endpoints over HTTP like the browser does: cookies in, cookies out. */
final class BackofficeAuth {

	static final String AUTH = "/api/backoffice/v1/auth";

	private final MockMvc mvc;

	BackofficeAuth(MockMvc mvc) {
		this.mvc = mvc;
	}

	MockHttpServletResponse login(String identifier, String password) throws Exception {
		return this.mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"identifier": "%s", "password": "%s"}
					""".formatted(identifier, password)))
			.andReturn().getResponse();
	}

	/** Logs in and returns the browser's cookie jar. */
	Session loginSession(String identifier, String password) throws Exception {
		MockHttpServletResponse response = login(identifier, password);
		if (response.getStatus() != 200 || response.getCookie("hy_b_at") == null) {
			throw new AssertionError("login failed: " + response.getStatus() + " " + response.getContentAsString());
		}
		return Session.from(response);
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
