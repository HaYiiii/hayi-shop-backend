package vn.hayi.shop.identity.domain;

import java.time.Duration;

/**
 * Temporary lock after repeated failures (API 01-security §6.3, Decision K2): {@code maxFailures} failed logins
 * within the latest {@code failureWindow} lock the account for {@code lockDuration}.
 * <p>
 * The lock must last at least the failure window: the window does not reach back past the end of the last lock
 * only while that lock is remembered, so a shorter lock would count pre-lock failures again.
 */
public record LoginLockPolicy(int maxFailures, Duration failureWindow, Duration lockDuration) {

	public LoginLockPolicy {
		if (maxFailures < 1 || !failureWindow.isPositive() || lockDuration.compareTo(failureWindow) < 0) {
			throw new IllegalArgumentException("Login lock policy needs maxFailures >= 1 and lockDuration >= failureWindow > 0, got "
					+ maxFailures + ", " + failureWindow + ", " + lockDuration);
		}
	}

}
