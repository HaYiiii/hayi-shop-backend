package vn.hayi.shop.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Temporary login lock (API 01-security §6.3): 5 failures with no gap over 15 minutes lock for 15 minutes. */
class UserLoginLockTest {

	private static final LoginLockPolicy POLICY = new LoginLockPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

	private static final Instant T0 = Instant.parse("2026-10-17T03:00:00Z");

	@Test
	void fifthConsecutiveFailureLocksForLockDuration() {
		User user = User.create(T0);
		Instant previous = null;
		for (int failure = 1; failure <= 4; failure++) {
			Instant now = T0.plusSeconds(failure * 60L);
			assertThat(user.registerFailedLogin(now, previous, POLICY)).isFalse();
			previous = now;
		}

		Instant fifth = T0.plusSeconds(300);
		assertThat(user.registerFailedLogin(fifth, previous, POLICY)).isTrue();

		assertThat(user.isLocked(fifth.plus(Duration.ofMinutes(14)))).isTrue();
		assertThat(user.lockRemaining(fifth.plus(Duration.ofMinutes(10)))).isEqualTo(Duration.ofMinutes(5));
		assertThat(user.isLocked(fifth.plus(Duration.ofMinutes(15)))).isFalse();
	}

	@Test
	void gapLongerThanWindowStartsANewStreak() {
		User user = User.create(T0);
		for (int failure = 0; failure < 4; failure++) {
			user.registerFailedLogin(T0, T0, POLICY);
		}

		assertThat(user.registerFailedLogin(T0.plus(Duration.ofMinutes(16)), T0, POLICY)).isFalse();
	}

	@Test
	void failureAfterLockExpiredStartsANewStreak() {
		User user = User.create(T0);
		for (int failure = 0; failure < 5; failure++) {
			user.registerFailedLogin(T0, T0, POLICY);
		}
		Instant afterLock = T0.plus(Duration.ofMinutes(15));

		assertThat(user.registerFailedLogin(afterLock, afterLock.minusSeconds(1), POLICY)).isFalse();
		assertThat(user.isLocked(afterLock)).isFalse();
	}

	@Test
	void successfulLoginClearsTheStreak() {
		User user = User.create(T0);
		for (int failure = 0; failure < 4; failure++) {
			user.registerFailedLogin(T0, T0, POLICY);
		}

		user.registerSuccessfulLogin(T0);

		assertThat(user.registerFailedLogin(T0, T0, POLICY)).isFalse();
	}

}
