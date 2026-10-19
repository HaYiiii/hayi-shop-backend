package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;

/**
 * Double-submit CSRF bound to the session (API 01-security §3.1): every unsafe request riding on auth cookies needs
 * {@code X-CSRF-Token} equal to {@code hy_csrf}, minted for the same session; otherwise {@code 403 CSRF_INVALID} and a
 * {@code CSRF_VALIDATION_FAILED} security event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeCsrfTest {

	/** Any unsafe Backoffice request; the CSRF check runs before routing. */
	private static final String UNSAFE_PATH = "/api/backoffice/v1/orders";

	@Autowired
	MockMvc mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	IdentityFixtures fixtures;

	BackofficeAuth auth;

	String email;

	UUID userId;

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.email = IdentityFixtures.uniqueEmail();
		this.userId = this.fixtures.storeAdmin(this.email);
	}

	@Test
	void unsafeRequestWithoutCsrfHeaderForbidden() throws Exception {
		Session session = login();

		assertProblem(unsafeRequest(session, false), 403, "CSRF_INVALID");
		assertThat(this.fixtures.securityEvents("CSRF_VALIDATION_FAILED", this.userId)).isEqualTo(1);
	}

	@Test
	void csrfHeaderDifferentFromCookieForbidden() throws Exception {
		Session session = login();
		Session other = login();

		MockHttpServletResponse response = this.auth.perform(post(UNSAFE_PATH).header("X-CSRF-Token", other.csrf()),
				session, false);

		assertProblem(response, 403, "CSRF_INVALID");
	}

	@Test
	void csrfTokenOfAnotherSessionForbidden() throws Exception {
		Session session = login();
		Session other = login();

		assertProblem(unsafeRequest(session.withCsrf(other.csrf()), true), 403, "CSRF_INVALID");
	}

	@Test
	void forgedCsrfTokenForbidden() throws Exception {
		Session session = login();

		assertProblem(unsafeRequest(session.withCsrf("forged.token"), true), 403, "CSRF_INVALID");
	}

	@Test
	void validCsrfTokenPasses() throws Exception {
		Session session = login();

		assertThat(unsafeRequest(session, true).getStatus()).isNotIn(401, 403);
	}

	@Test
	void refreshWithoutCsrfForbidden() throws Exception {
		Session session = login();

		assertProblem(this.auth.refresh(session.withoutCsrf()), 403, "CSRF_INVALID");
	}

	@Test
	void refreshWithCsrfOfAnotherSessionForbidden() throws Exception {
		Session session = login();
		Session other = login();

		assertProblem(this.auth.refresh(session.withCsrf(other.csrf())), 403, "CSRF_INVALID");
		assertThat(this.auth.refresh(other).getStatus()).isEqualTo(204);
	}

	@Test
	void logoutWithoutCsrfForbiddenAndSessionStaysAlive() throws Exception {
		Session session = login();

		assertProblem(this.auth.logout(session.withoutCsrf()), 403, "CSRF_INVALID");
		assertThat(this.auth.csrf(session).getStatus()).isEqualTo(204);
	}

	@Test
	void reissuedCsrfTokenWorksForTheSession() throws Exception {
		Session session = login();

		Session reissued = session.update(this.auth.csrf(session));

		assertThat(reissued.csrf()).isNotEqualTo(session.csrf());
		assertThat(unsafeRequest(reissued, true).getStatus()).isNotIn(401, 403);
	}

	@Test
	void loginNeedsNoCsrf() throws Exception {
		assertThat(this.auth.login(this.email, IdentityFixtures.PASSWORD).getStatus()).isEqualTo(200);
	}

	private MockHttpServletResponse unsafeRequest(Session session, boolean withCsrfHeader) throws Exception {
		return this.auth.perform(post(UNSAFE_PATH), session, withCsrfHeader);
	}

	private Session login() throws Exception {
		return this.auth.loginSession(this.email, IdentityFixtures.PASSWORD);
	}

}
