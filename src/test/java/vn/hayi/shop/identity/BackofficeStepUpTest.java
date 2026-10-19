package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;
import vn.hayi.shop.identity.BackofficeAuth.Session;
import vn.hayi.shop.platform.security.RequiresStepUp;

/**
 * Step-up for actions marked S (API 01-security §8, Decision L7): fresh re-authentication within 5 minutes, by TOTP
 * or by password for a user without MFA; repeated failures end the session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class,
		BackofficeStepUpTest.SensitiveActionProbe.class })
class BackofficeStepUpTest {

	static final String SENSITIVE_ACTION = "/api/backoffice/v1/test-probe/sensitive-action";

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	BackofficeAuth auth;

	UUID userId;

	Session session;

	@BeforeEach
	void setUp() throws Exception {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		String email = IdentityFixtures.uniqueEmail();
		this.userId = this.fixtures.storeAdmin(email);
		this.session = this.auth.loginSession(email, IdentityFixtures.PASSWORD);
		// The login used the current TOTP step; the authenticator shows the next one by now.
		this.clock.advance(Duration.ofSeconds(30));
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void sensitiveActionWithoutStepUpGets403() throws Exception {
		assertProblem(sensitiveAction(), 403, "STEP_UP_REQUIRED");
	}

	@Test
	void stepUpWithTotpAllowsActionForFiveMinutes() throws Exception {
		assertThat(this.auth.stepUp(this.session, currentCode(), null).getStatus()).isEqualTo(204);

		assertThat(sensitiveAction().getStatus()).isEqualTo(204);
		this.clock.advance(Duration.ofMinutes(4));
		assertThat(sensitiveAction().getStatus()).isEqualTo(204);
		this.clock.advance(Duration.ofMinutes(1));
		assertProblem(sensitiveAction(), 403, "STEP_UP_REQUIRED");
	}

	@Test
	void stepUpWithPasswordWhenNoMfa() throws Exception {
		this.fixtures.disableMfa(this.userId);

		assertProblem(this.auth.stepUp(this.session, null, "not the password"), 401, "AUTH_INVALID_CREDENTIALS");
		assertThat(this.auth.stepUp(this.session, null, IdentityFixtures.PASSWORD).getStatus()).isEqualTo(204);

		assertThat(sensitiveAction().getStatus()).isEqualTo(204);
	}

	@Test
	void userWithMfaCannotStepUpWithPassword() throws Exception {
		assertProblem(this.auth.stepUp(this.session, null, IdentityFixtures.PASSWORD), 401, "MFA_CODE_INVALID");

		assertProblem(sensitiveAction(), 403, "STEP_UP_REQUIRED");
	}

	@Test
	void wrongStepUpCodeRejected() throws Exception {
		assertProblem(this.auth.stepUp(this.session, "000000".equals(currentCode()) ? "111111" : "000000", null), 401,
				"MFA_CODE_INVALID");

		assertThat(this.fixtures.securityEvents("MFA_FAILED", this.userId)).isEqualTo(1);
		assertProblem(sensitiveAction(), 403, "STEP_UP_REQUIRED");
	}

	@Test
	void fifthFailedStepUpRevokesSession() throws Exception {
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertProblem(this.auth.stepUp(this.session, null, "wrong"), 401, "MFA_CODE_INVALID");
		}

		assertProblem(this.auth.stepUp(this.session, null, "wrong"), 401, "AUTH_SESSION_REVOKED");

		assertProblem(this.auth.csrf(this.session), 401, "AUTH_SESSION_REVOKED");
		assertThat(this.fixtures.securityEvents("SESSION_REVOKED", this.userId)).isEqualTo(1);
	}

	@Test
	void stepUpFailuresOlderThanTheWindowDoNotRevoke() throws Exception {
		for (int attempt = 1; attempt <= 4; attempt++) {
			this.auth.stepUp(this.session, null, "wrong");
		}
		this.clock.advance(Duration.ofMinutes(16));
		this.session = this.session.update(this.auth.refresh(this.session));

		assertProblem(this.auth.stepUp(this.session, null, "wrong"), 401, "MFA_CODE_INVALID");
	}

	private MockHttpServletResponse sensitiveAction() throws Exception {
		return this.auth.perform(post(SENSITIVE_ACTION), this.session, true);
	}

	private String currentCode() {
		return BackofficeAuth.totp(IdentityFixtures.TOTP_SECRET, this.clock.instant());
	}

	/** Stands in for a real action marked S (refund approval, branch close) until those exist. */
	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class SensitiveActionProbe {

		@RequiresStepUp
		@PostMapping(SENSITIVE_ACTION)
		ResponseEntity<Void> sensitiveAction() {
			return ResponseEntity.noContent().build();
		}

	}

}
