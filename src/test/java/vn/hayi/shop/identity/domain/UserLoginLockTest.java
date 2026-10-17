package vn.hayi.shop.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Temporary login lock (API 01-security §6.3, Decision K2): 5 failures within the latest 15 minutes lock for
 * 15 minutes; the window never reaches back before the last successful login or the end of the last lock.
 */
class UserLoginLockTest {

	private static final LoginLockPolicy POLICY = new LoginLockPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

	private static final Instant T0 = Instant.parse("2026-10-17T03:00:00Z");

	@Test
	void fifthFailureInTheWindowLocksForLockDuration() {
		User user = User.create(T0);
		assertThat(user.registerFailedLogin(T0, 3, POLICY)).isFalse();

		assertThat(user.registerFailedLogin(T0, 4, POLICY)).isTrue();

		assertThat(user.isLocked(T0.plus(Duration.ofMinutes(14)))).isTrue();
		assertThat(user.lockRemaining(T0.plus(Duration.ofMinutes(10)))).isEqualTo(Duration.ofMinutes(5));
		assertThat(user.isLocked(T0.plus(Duration.ofMinutes(15)))).isFalse();
	}

	@Test
	void windowIsTheLatestFifteenMinutes() {
		User user = User.create(T0.minus(Duration.ofDays(1)));

		assertThat(user.failureWindowStart(T0, POLICY)).isEqualTo(T0.minus(Duration.ofMinutes(15)));
	}

	@Test
	void windowStartsAfterTheLastSuccessfulLogin() {
		User user = User.create(T0);
		user.registerSuccessfulLogin(T0);

		assertThat(user.failureWindowStart(T0.plus(Duration.ofMinutes(5)), POLICY)).isEqualTo(T0);
	}

	@Test
	void windowStartsWhenTheLastLockExpired() {
		User user = User.create(T0);
		user.registerFailedLogin(T0, 4, POLICY);
		Instant lockEnd = T0.plus(Duration.ofMinutes(15));

		assertThat(user.failureWindowStart(lockEnd.plus(Duration.ofMinutes(1)), POLICY)).isEqualTo(lockEnd);
		assertThat(user.registerFailedLogin(lockEnd.plus(Duration.ofMinutes(1)), 0, POLICY)).isFalse();
		assertThat(user.isLocked(lockEnd.plus(Duration.ofMinutes(1)))).isFalse();
	}

}
