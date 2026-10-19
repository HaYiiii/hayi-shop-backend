package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.time.Duration;
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
 * Session lifecycle over cookies: refresh rotation with a 10s grace, reuse → whole session revoked, logout, and
 * revocation taking effect on the very next request (Identity §2.5–2.6, API 01-security §4–5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeSessionTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	BackofficeAuth auth;

	String email;

	UUID userId;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.email = IdentityFixtures.uniqueEmail();
		this.userId = this.fixtures.storeAdmin(this.email);
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void refreshRotatesTokens() throws Exception {
		Session session = login();

		MockHttpServletResponse response = this.auth.refresh(session);

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(204);
		Session rotated = session.update(response);
		assertThat(rotated.refresh()).isNotEqualTo(session.refresh());
		assertThat(rotated.access()).isNotEqualTo(session.access());
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_b_rt")).hasValueSatisfying(cookie -> assertThat(cookie)
			.contains("Path=/api/backoffice/v1/auth", "HttpOnly", "SameSite=Strict"));
		assertThat(this.auth.csrf(rotated).getStatus()).isEqualTo(204);
		assertThat(this.auth.refresh(rotated).getStatus()).isEqualTo(204);
	}

	@Test
	void expiredAccessTokenAsksForRefresh() throws Exception {
		Session session = login();
		this.clock.advance(Duration.ofMinutes(11));

		assertProblem(this.auth.csrf(session), 401, "AUTH_TOKEN_EXPIRED");

		Session refreshed = session.update(this.auth.refresh(session));
		assertThat(this.auth.csrf(refreshed).getStatus()).isEqualTo(204);
	}

	@Test
	void rotatedTokenReusedWithinGraceReturnsTheCurrentPair() throws Exception {
		Session session = login();
		Session firstTab = session.update(this.auth.refresh(session));
		this.clock.advance(Duration.ofSeconds(5));

		MockHttpServletResponse secondTab = this.auth.refresh(session);

		assertThat(secondTab.getStatus()).isEqualTo(204);
		assertThat(session.update(secondTab).refresh()).isEqualTo(firstTab.refresh());
		assertThat(this.auth.refresh(firstTab).getStatus()).isEqualTo(204);
	}

	@Test
	void rotatedTokenReusedAfterGraceRevokesSession() throws Exception {
		Session session = login();
		Session rotated = session.update(this.auth.refresh(session));
		this.clock.advance(Duration.ofSeconds(11));

		assertProblem(this.auth.refresh(session), 401, "AUTH_SESSION_REVOKED");

		assertThat(this.fixtures.securityEvents("REFRESH_TOKEN_REUSE_DETECTED", this.userId)).isEqualTo(1);
		assertProblem(this.auth.refresh(rotated), 401, "AUTH_SESSION_REVOKED");
		assertProblem(this.auth.csrf(rotated), 401, "AUTH_SESSION_REVOKED");
	}

	@Test
	void revokedSessionRejectedImmediately() throws Exception {
		Session session = login();
		assertThat(this.auth.csrf(session).getStatus()).isEqualTo(204);

		assertThat(this.auth.logout(session).getStatus()).isEqualTo(204);

		assertProblem(this.auth.csrf(session), 401, "AUTH_SESSION_REVOKED");
	}

	@Test
	void sessionRevokedByAnotherInstanceRefusedOnceTheCacheTtlPasses() throws Exception {
		Session session = login();
		assertThat(this.auth.csrf(session).getStatus()).isEqualTo(204);

		this.fixtures.revokeSessionsElsewhere(this.userId);
		this.clock.advance(Duration.ofSeconds(31));

		assertProblem(this.auth.csrf(session), 401, "AUTH_SESSION_REVOKED");
	}

	@Test
	void logoutRevokesSessionAndClearsCookies() throws Exception {
		Session session = login();

		MockHttpServletResponse response = this.auth.logout(session);

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_b_at")).hasValueSatisfying(cookie -> assertThat(cookie).contains("Max-Age=0"));
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_b_rt")).hasValueSatisfying(cookie -> assertThat(cookie).contains("Max-Age=0"));
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_csrf")).hasValueSatisfying(cookie -> assertThat(cookie).contains("Max-Age=0"));
		assertThat(this.fixtures.securityEvents("SESSION_REVOKED", this.userId)).isEqualTo(1);
		assertProblem(this.auth.refresh(session), 401, "AUTH_SESSION_REVOKED");
		assertThat(this.auth.logout(session).getStatus()).isEqualTo(204);
	}

	@Test
	void logoutWithOnlyTheAccessCookieStillRevokesTheSession() throws Exception {
		Session session = login();

		assertThat(this.auth.logout(new Session(session.access(), null, session.csrf())).getStatus()).isEqualTo(204);

		assertProblem(this.auth.csrf(session), 401, "AUTH_SESSION_REVOKED");
		assertProblem(this.auth.refresh(session), 401, "AUTH_SESSION_REVOKED");
	}

	@Test
	void logoutKeepsOtherSessionsOfTheUser() throws Exception {
		Session laptop = login();
		Session counter = login();

		this.auth.logout(laptop);

		assertThat(this.auth.csrf(counter).getStatus()).isEqualTo(204);
	}

	@Test
	void unknownRefreshTokenRejected() throws Exception {
		Session session = login();

		assertProblem(this.auth.refresh(new Session(session.access(), "forged", session.csrf())), 401, "AUTH_REQUIRED");
		assertProblem(this.auth.refresh(new Session(session.access(), null, session.csrf())), 401, "AUTH_REQUIRED");
	}

	@Test
	void refreshTokenExpiresAfterIdleTime() throws Exception {
		Session session = login();
		this.clock.advance(Duration.ofHours(12));

		assertProblem(this.auth.refresh(session), 401, "AUTH_REQUIRED");
	}

	@Test
	void sessionEndsAtItsAbsoluteLifetime() throws Exception {
		Session session = login();
		for (int refresh = 1; refresh <= 15; refresh++) {
			this.clock.advance(Duration.ofHours(11));
			session = session.update(this.auth.refresh(session));
		}
		this.clock.advance(Duration.ofHours(4));

		assertProblem(this.auth.refresh(session), 401, "AUTH_SESSION_REVOKED");
	}

	private Session login() throws Exception {
		return this.auth.loginSession(this.email, IdentityFixtures.PASSWORD);
	}

}
