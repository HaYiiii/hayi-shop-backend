package vn.hayi.shop.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * Backoffice login with email + password (Identity §2.2, §2.5, API 01-security §3, §6.2–6.3, Decision K2–K4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MutableClockConfiguration.class, IdentityFixtures.class })
class BackofficeLoginTest {

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
	void correctPasswordAsksForTheMfaCodeBeforeAnySession() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);

		MockHttpServletResponse response = this.auth.login(this.email, IdentityFixtures.PASSWORD);

		assertThat(response.getStatus()).isEqualTo(200);
		String challengeId = JsonPath.read(response.getContentAsString(), "$.mfaChallengeId");
		assertThat(response.getContentAsString()).isEqualTo("""
				{"passwordChangeRequired":false,"changeToken":null,"mfaRequired":true,"mfaChallengeId":"%s",\
				"mfaEnrollmentRequired":false,"enrollmentToken":null}""".formatted(challengeId));
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("LOGIN_SUCCESS", userId)).isEqualTo(1);
		assertThat(this.fixtures.securityEvents("SESSION_CREATED", userId)).isZero();
	}

	@Test
	void emailIsMatchedCaseInsensitively() throws Exception {
		this.fixtures.storeAdmin(this.email);

		assertThat(this.auth.login("  " + this.email.toUpperCase() + " ", IdentityFixtures.PASSWORD).getStatus())
			.isEqualTo(200);
	}

	@Test
	void wrongPasswordRejected() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);

		MockHttpServletResponse response = this.auth.login(this.email, "not the password");

		assertProblem(response, 401, "AUTH_INVALID_CREDENTIALS");
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("LOGIN_FAILED", userId)).isEqualTo(1);
	}

	@Test
	void unknownEmailRejectedSameAsWrongPassword() throws Exception {
		assertProblem(this.auth.login(this.email, IdentityFixtures.PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void fifthFailureLocksAccount() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertProblem(this.auth.login(this.email, "wrong " + attempt), 401, "AUTH_INVALID_CREDENTIALS");
		}

		MockHttpServletResponse fifth = this.auth.login(this.email, "wrong 5");

		assertProblem(fifth, 429, "AUTH_LOGIN_LOCKED");
		assertThat(fifth.getHeader("Retry-After")).isEqualTo("900");
		assertThat(this.fixtures.securityEvents("ACCOUNT_LOCKED", userId)).isEqualTo(1);
	}

	@Test
	void lockedAccountRejectedEvenWithCorrectPasswordUntilLockExpires() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email);
		lockAccount();
		this.clock.advance(Duration.ofMinutes(10));

		MockHttpServletResponse blocked = this.auth.login(this.email, IdentityFixtures.PASSWORD);

		assertProblem(blocked, 429, "AUTH_LOGIN_LOCKED");
		assertThat(blocked.getHeader("Retry-After")).isEqualTo("300");
		assertThat(this.fixtures.securityEvents("LOGIN_BLOCKED", userId)).isEqualTo(1);

		this.clock.advance(Duration.ofMinutes(5));
		assertThat(this.auth.login(this.email, IdentityFixtures.PASSWORD).getStatus()).isEqualTo(200);
	}

	@Test
	void failuresSpreadBeyondTheWindowDoNotLock() throws Exception {
		this.fixtures.storeAdmin(this.email);
		for (int attempt = 1; attempt <= 4; attempt++) {
			this.auth.login(this.email, "wrong " + attempt);
		}
		this.clock.advance(Duration.ofMinutes(16));

		assertProblem(this.auth.login(this.email, "wrong again"), 401, "AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void failuresFourteenMinutesApartNeverLock() throws Exception {
		this.fixtures.storeAdmin(this.email);
		for (int attempt = 1; attempt <= 6; attempt++) {
			assertProblem(this.auth.login(this.email, "wrong " + attempt), 401, "AUTH_INVALID_CREDENTIALS");
			this.clock.advance(Duration.ofMinutes(14));
		}
	}

	@Test
	void fiveFailuresWithinTheLatestFifteenMinutesLock() throws Exception {
		this.fixtures.storeAdmin(this.email);
		this.auth.login(this.email, "early 1");
		this.clock.advance(Duration.ofMinutes(10));
		this.auth.login(this.email, "early 2");
		this.clock.advance(Duration.ofMinutes(10));
		for (int attempt = 1; attempt <= 3; attempt++) {
			assertProblem(this.auth.login(this.email, "wrong " + attempt), 401, "AUTH_INVALID_CREDENTIALS");
		}

		assertProblem(this.auth.login(this.email, "wrong 4"), 429, "AUTH_LOGIN_LOCKED");
	}

	@Test
	void accountWithoutBackofficeAccessRejectedLikeWrongPassword() throws Exception {
		UUID userId = this.fixtures.withoutBackofficeAccess(this.email);
		for (int attempt = 1; attempt <= 4; attempt++) {
			this.auth.login(this.email, "wrong " + attempt);
		}

		MockHttpServletResponse response = this.auth.login(this.email, IdentityFixtures.PASSWORD);

		assertProblem(response, 401, "AUTH_INVALID_CREDENTIALS");
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("LOGIN_SUCCESS", userId)).isZero();
		assertProblem(this.auth.login(this.email, "wrong 5"), 429, "AUTH_LOGIN_LOCKED");
	}

	@Test
	void successfulLoginResetsFailureCount() throws Exception {
		this.fixtures.storeAdmin(this.email);
		for (int attempt = 1; attempt <= 4; attempt++) {
			this.auth.login(this.email, "wrong " + attempt);
		}
		this.auth.loginSession(this.email, IdentityFixtures.PASSWORD);

		assertProblem(this.auth.login(this.email, "wrong 5"), 401, "AUTH_INVALID_CREDENTIALS");
	}

	@Test
	void loginWithTemporaryPasswordRequiresChangeFirst() throws Exception {
		this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);

		MockHttpServletResponse response = this.auth.login(this.email, IdentityFixtures.PASSWORD);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(response.getContentAsString(), "$.passwordChangeRequired")).isTrue();
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.changeToken")).isNotBlank();
		assertThat(response.getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void firstPasswordChangeThenLoginWithNewPassword() throws Exception {
		UUID userId = this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);
		String newPassword = "a much longer new passphrase";

		MockHttpServletResponse change = this.auth.changeFirstPassword(changeToken(), newPassword);

		assertThat(change.getStatus()).isEqualTo(204);
		assertThat(change.getHeaders("Set-Cookie")).isEmpty();
		assertThat(this.fixtures.securityEvents("PASSWORD_CHANGED", userId)).isEqualTo(1);
		assertProblem(this.auth.login(this.email, IdentityFixtures.PASSWORD), 401, "AUTH_INVALID_CREDENTIALS");
		assertThat(this.auth.loginSession(this.email, newPassword).access()).isNotBlank();
	}

	@Test
	void changeTokenCannotBeUsedTwice() throws Exception {
		this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);
		String changeToken = changeToken();
		this.auth.changeFirstPassword(changeToken, "a much longer new passphrase");

		assertProblem(this.auth.changeFirstPassword(changeToken, "another long passphrase!"), 401, "AUTH_REQUIRED");
	}

	@Test
	void changeTokenUsedConcurrentlySucceedsOnce() throws Exception {
		this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);
		String changeToken = changeToken();
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> change = () -> {
			start.await();
			return this.auth.changeFirstPassword(changeToken, "a much longer new passphrase").getStatus();
		};

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(change);
			Future<Integer> second = executor.submit(change);
			start.countDown();

			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(204, 401);
		}
	}

	@Test
	void expiredChangeTokenRejected() throws Exception {
		this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);
		String changeToken = changeToken();
		this.clock.advance(Duration.ofMinutes(6));

		assertProblem(this.auth.changeFirstPassword(changeToken, "a much longer new passphrase"), 401, "AUTH_REQUIRED");
	}

	@Test
	void firstPasswordChangeEnforcesPolicy() throws Exception {
		this.fixtures.storeAdmin(this.email, IdentityFixtures.PASSWORD, true);
		String changeToken = changeToken();

		assertProblem(this.auth.changeFirstPassword(changeToken, "short pass"), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(this.auth.changeFirstPassword(changeToken, "x".repeat(129)), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(this.auth.changeFirstPassword(changeToken, "Password1234"), 422, "AUTH_PASSWORD_POLICY_VIOLATION");
		assertProblem(this.auth.changeFirstPassword(changeToken, IdentityFixtures.PASSWORD), 422,
				"AUTH_PASSWORD_POLICY_VIOLATION");
	}

	private String changeToken() throws Exception {
		return JsonPath.read(this.auth.login(this.email, IdentityFixtures.PASSWORD).getContentAsString(), "$.changeToken");
	}

	private void lockAccount() throws Exception {
		for (int attempt = 1; attempt <= 5; attempt++) {
			this.auth.login(this.email, "wrong " + attempt);
		}
	}

	static void assertProblem(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(code);
	}

}
