package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.hayi.shop.identity.BackofficeLoginTest.assertProblem;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

import com.jayway.jsonpath.JsonPath;
import vn.hayi.shop.MutableClock;
import vn.hayi.shop.MutableClockConfiguration;
import vn.hayi.shop.TestcontainersConfiguration;

/**
 * MFA at Backoffice login (Identity §2.4, API 01-security §6.2, Decision D4, L1–L6): STORE roles enroll TOTP before
 * their first session, then answer a TOTP or recovery code at every login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeMfaTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	IdentityFixtures fixtures;

	@Autowired
	MutableClock clock;

	BackofficeAuth auth;

	String email;

	@BeforeEach
	void setUp() {
		this.auth = new BackofficeAuth(this.mvc, this.clock);
		this.email = IdentityFixtures.uniqueEmail();
	}

	@AfterEach
	void resetClock() {
		this.clock.reset();
	}

	@Test
	void storeUserWithoutMfaMustEnrollBeforeSession() throws Exception {
		this.fixtures.storeAdminWithoutMfa(this.email);

		MockHttpServletResponse response = this.auth.login(this.email, IdentityFixtures.PASSWORD);

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		String body = response.getContentAsString();
		assertThat(JsonPath.<Boolean>read(body, "$.mfaEnrollmentRequired")).isTrue();
		assertThat(JsonPath.<String>read(body, "$.enrollmentToken")).isNotBlank();
		assertThat(JsonPath.<Boolean>read(body, "$.mfaRequired")).isFalse();
		assertThat(JsonPath.<String>read(body, "$.mfaChallengeId")).isNull();
		assertThat(JsonPath.<Boolean>read(body, "$.passwordChangeRequired")).isFalse();
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void enrollConfirmIssuesSessionAndTenRecoveryCodes() throws Exception {
		UUID userId = this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		MockHttpServletResponse enroll = this.auth.startEnrollment(token);
		assertThat(enroll.getStatus()).as(enroll.getContentAsString()).isEqualTo(200);
		String secret = JsonPath.read(enroll.getContentAsString(), "$.secret");
		assertThat(JsonPath.<String>read(enroll.getContentAsString(), "$.otpauthUri"))
			.startsWith("otpauth://totp/").contains("secret=" + secret, "issuer=", "digits=6", "period=30");
		assertThat(enroll.getHeaders("Set-Cookie")).isEmpty();

		MockHttpServletResponse confirm = this.auth.confirmEnrollment(token, BackofficeAuth.totp(secret, this.clock.instant()));

		assertThat(confirm.getStatus()).as(confirm.getContentAsString()).isEqualTo(200);
		assertThat(JsonPath.<List<String>>read(confirm.getContentAsString(), "$.recoveryCodes")).hasSize(10)
			.allMatch(code -> code.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"))
			.doesNotHaveDuplicates();
		BackofficeAuth.Session session = BackofficeAuth.Session.from(confirm);
		assertThat(this.auth.csrf(session).getStatus()).isEqualTo(204);
		assertThat(this.fixtures.securityEvents("MFA_ENABLED", userId)).isEqualTo(1);
		assertThat(this.fixtures.securityEvents("SESSION_CREATED", userId)).isEqualTo(1);
	}

	@Test
	void enrollmentTokenCannotBeReusedOnceMfaIsEnabled() throws Exception {
		this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		String secret = JsonPath.read(this.auth.startEnrollment(token).getContentAsString(), "$.secret");
		this.auth.confirmEnrollment(token, BackofficeAuth.totp(secret, this.clock.instant()));

		assertProblem(this.auth.startEnrollment(token), 401, "AUTH_REQUIRED");
		assertProblem(this.auth.confirmEnrollment(token, BackofficeAuth.totp(secret, this.clock.instant())), 401,
				"AUTH_REQUIRED");
	}

	@Test
	void enrollRestartReplacesPendingFactor() throws Exception {
		this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		String first = JsonPath.read(this.auth.startEnrollment(token).getContentAsString(), "$.secret");
		String second = JsonPath.read(this.auth.startEnrollment(token).getContentAsString(), "$.secret");

		assertThat(second).isNotEqualTo(first);
		assertProblem(this.auth.confirmEnrollment(token, BackofficeAuth.totp(first, this.clock.instant())), 401,
				"MFA_CODE_INVALID");
		assertThat(this.auth.confirmEnrollment(token, BackofficeAuth.totp(second, this.clock.instant())).getStatus())
			.isEqualTo(200);
	}

	@Test
	void wrongEnrollmentCodeRejected() throws Exception {
		UUID userId = this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		this.auth.startEnrollment(token);

		MockHttpServletResponse confirm = this.auth.confirmEnrollment(token, "000000");

		assertProblem(confirm, 401, "MFA_CODE_INVALID");
		assertThat(confirm.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("MFA_FAILED", userId)).isEqualTo(1);
	}

	@Test
	void expiredEnrollmentTokenRejected() throws Exception {
		this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		this.clock.advance(Duration.ofMinutes(11));

		assertProblem(this.auth.startEnrollment(token), 401, "AUTH_REQUIRED");
	}

	/**
	 * Fail-closed (Decision L6): tests leave {@code hayi.security.require-mfa} unset, so this is the default every
	 * environment gets. Whatever the state of the STORE user, the password step alone never yields a session cookie.
	 */
	@Test
	void storeLoginNeverSetsSessionCookieBeforeMfa() throws Exception {
		String withoutMfa = IdentityFixtures.uniqueEmail();
		String withMfa = IdentityFixtures.uniqueEmail();
		String temporaryPassword = IdentityFixtures.uniqueEmail();
		this.fixtures.storeAdminWithoutMfa(withoutMfa);
		this.fixtures.storeAdmin(withMfa);
		this.fixtures.storeAdmin(temporaryPassword, IdentityFixtures.PASSWORD, true);

		for (String email : List.of(withoutMfa, withMfa, temporaryPassword)) {
			MockHttpServletResponse response = this.auth.login(email, IdentityFixtures.PASSWORD);

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeaders("Set-Cookie")).as(email).isEmpty();
		}
		String enrollmentToken = JsonPath.read(this.auth.login(withoutMfa, IdentityFixtures.PASSWORD).getContentAsString(),
				"$.enrollmentToken");
		assertThat(this.auth.startEnrollment(enrollmentToken).getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void correctTotpIssuesSession() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);
		String challengeId = challengeId();

		MockHttpServletResponse response = this.auth.verifyMfa(challengeId, currentCode());

		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(204);
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_b_at")).hasValueSatisfying(cookie -> assertThat(cookie)
			.contains("Path=/api/backoffice", "Max-Age=600", "Secure", "HttpOnly", "SameSite=Lax"));
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_b_rt")).hasValueSatisfying(cookie -> assertThat(cookie)
			.contains("Path=/api/backoffice/v1/auth", "Secure", "HttpOnly", "SameSite=Strict"));
		assertThat(BackofficeAuth.setCookieHeader(response, "hy_csrf")).hasValueSatisfying(cookie -> assertThat(cookie)
			.contains("Path=/", "Secure", "SameSite=Lax").doesNotContain("HttpOnly"));
		assertThat(this.fixtures.securityEvents("SESSION_CREATED", userId)).isEqualTo(1);
		assertThat(this.auth.csrf(BackofficeAuth.Session.from(response)).getStatus()).isEqualTo(204);
		assertProblem(this.auth.verifyMfa(challengeId, currentCode()), 401, "AUTH_REQUIRED");
	}

	@Test
	void totpWithinOneStepDriftAccepted() throws Exception {
		this.fixtures.storeAdmin(this.email);

		String previousStep = BackofficeAuth.totp(IdentityFixtures.TOTP_SECRET, this.clock.instant().minusSeconds(30));

		assertThat(this.auth.verifyMfa(challengeId(), previousStep).getStatus()).isEqualTo(204);
	}

	@Test
	void wrongTotpRejected() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);

		MockHttpServletResponse response = this.auth.verifyMfa(challengeId(), wrongCode());

		assertProblem(response, 401, "MFA_CODE_INVALID");
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("MFA_FAILED", userId)).isEqualTo(1);
	}

	@Test
	void fifthWrongCodeLocksChallenge() throws Exception {
		this.fixtures.storeAdmin(this.email);
		String challengeId = challengeId();
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertProblem(this.auth.verifyMfa(challengeId, wrongCode()), 401, "MFA_CODE_INVALID");
		}

		assertProblem(this.auth.verifyMfa(challengeId, wrongCode()), 401, "MFA_CHALLENGE_LOCKED");

		assertProblem(this.auth.verifyMfa(challengeId, currentCode()), 401, "MFA_CHALLENGE_LOCKED");
		assertThat(this.auth.verifyMfa(challengeId(), currentCode()).getStatus()).isEqualTo(204);
	}

	@Test
	void mfaFailuresDoNotCountTowardsPasswordFailures() throws Exception {
		this.fixtures.storeAdmin(this.email);
		wrongCodes(challengeId(), 5);
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertProblem(this.auth.login(this.email, "not the password"), 401, "AUTH_INVALID_CREDENTIALS");
		}

		assertThat(this.auth.login(this.email, IdentityFixtures.PASSWORD).getStatus()).isEqualTo(200);
	}

	@Test
	void tenthWrongMfaCodeAcrossChallengesLocksLogin() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);
		wrongCodes(challengeId(), 4);
		wrongCodes(challengeId(), 4);
		String third = challengeId();
		wrongCodes(third, 1);

		assertProblem(this.auth.verifyMfa(third, wrongCode()), 429, "AUTH_LOGIN_LOCKED");

		assertProblem(this.auth.verifyMfa(third, currentCode()), 429, "AUTH_LOGIN_LOCKED");
		assertProblem(this.auth.login(this.email, IdentityFixtures.PASSWORD), 429, "AUTH_LOGIN_LOCKED");
		assertThat(this.fixtures.securityEvents("ACCOUNT_LOCKED", userId)).isEqualTo(1);
		this.clock.advance(Duration.ofMinutes(15));
		assertThat(this.auth.verifyMfa(challengeId(), currentCode()).getStatus()).isEqualTo(204);
	}

	@Test
	void mfaFailuresOlderThanTheWindowDoNotLock() throws Exception {
		this.fixtures.storeAdmin(this.email);
		wrongCodes(challengeId(), 4);
		wrongCodes(challengeId(), 4);
		wrongCodes(challengeId(), 1);
		this.clock.advance(Duration.ofMinutes(16));

		assertProblem(this.auth.verifyMfa(challengeId(), wrongCode()), 401, "MFA_CODE_INVALID");
	}

	@Test
	void completedLoginResetsMfaFailures() throws Exception {
		this.fixtures.storeAdmin(this.email);
		wrongCodes(challengeId(), 4);
		wrongCodes(challengeId(), 4);
		String third = challengeId();
		wrongCodes(third, 1);
		assertThat(this.auth.verifyMfa(third, currentCode()).getStatus()).isEqualTo(204);
		this.clock.advance(Duration.ofSeconds(30));

		assertProblem(this.auth.verifyMfa(challengeId(), wrongCode()), 401, "MFA_CODE_INVALID");
	}

	@Test
	void newLoginInvalidatesEarlierChallenge() throws Exception {
		this.fixtures.storeAdmin(this.email);
		String earlier = challengeId();
		String latest = challengeId();

		assertProblem(this.auth.verifyMfa(earlier, currentCode()), 401, "AUTH_REQUIRED");

		assertThat(this.auth.verifyMfa(latest, currentCode()).getStatus()).isEqualTo(204);
	}

	@Test
	void sameTotpStepNotAcceptedTwice() throws Exception {
		this.fixtures.storeAdmin(this.email);
		String code = currentCode();
		assertThat(this.auth.verifyMfa(challengeId(), code).getStatus()).isEqualTo(204);

		assertProblem(this.auth.verifyMfa(challengeId(), code), 401, "MFA_CODE_INVALID");
	}

	@Test
	void expiredChallengeRejected() throws Exception {
		this.fixtures.storeAdmin(this.email);
		String challengeId = challengeId();
		this.clock.advance(Duration.ofMinutes(5));

		assertProblem(this.auth.verifyMfa(challengeId, currentCode()), 401, "AUTH_REQUIRED");
	}

	@Test
	void recoveryCodeIssuesSessionOnce() throws Exception {
		this.fixtures.storeAdminWithoutMfa(this.email);
		String token = enrollmentToken();
		String secret = JsonPath.read(this.auth.startEnrollment(token).getContentAsString(), "$.secret");
		List<String> recoveryCodes = JsonPath.read(this.auth
			.confirmEnrollment(token, BackofficeAuth.totp(secret, this.clock.instant()))
			.getContentAsString(), "$.recoveryCodes");
		String recoveryCode = recoveryCodes.get(3);

		assertThat(this.auth.verifyMfa(challengeId(), recoveryCode.toLowerCase(Locale.ROOT).replace("-", "")).getStatus())
			.isEqualTo(204);

		assertProblem(this.auth.verifyMfa(challengeId(), recoveryCode), 401, "MFA_CODE_INVALID");
		assertThat(this.auth.verifyMfa(challengeId(), recoveryCodes.get(4)).getStatus()).isEqualTo(204);
	}

	private String challengeId() throws Exception {
		MockHttpServletResponse login = this.auth.login(this.email, IdentityFixtures.PASSWORD);
		assertThat(login.getStatus()).as(login.getContentAsString()).isEqualTo(200);
		return JsonPath.read(login.getContentAsString(), "$.mfaChallengeId");
	}

	private void wrongCodes(String challengeId, int count) throws Exception {
		for (int attempt = 1; attempt <= count; attempt++) {
			MockHttpServletResponse response = this.auth.verifyMfa(challengeId, wrongCode());
			assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
		}
	}

	private String currentCode() {
		return BackofficeAuth.totp(IdentityFixtures.TOTP_SECRET, this.clock.instant());
	}

	/** A 6-digit code that none of the accepted steps around now produces. */
	private String wrongCode() {
		List<String> accepted = List.of(-30, 0, 30).stream()
			.map(offset -> BackofficeAuth.totp(IdentityFixtures.TOTP_SECRET, this.clock.instant().plusSeconds(offset)))
			.toList();
		return List.of("000000", "111111", "222222", "333333").stream()
			.filter(code -> !accepted.contains(code))
			.findFirst()
			.orElseThrow();
	}

	private String enrollmentToken() throws Exception {
		return JsonPath.read(this.auth.login(this.email, IdentityFixtures.PASSWORD).getContentAsString(), "$.enrollmentToken");
	}

}
