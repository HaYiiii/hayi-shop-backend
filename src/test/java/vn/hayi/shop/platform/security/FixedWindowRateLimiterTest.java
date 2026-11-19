package vn.hayi.shop.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import vn.hayi.shop.MutableClock;

/** The limiter's memory stays bounded however many clients appear (Decision W9, review of PR #90). */
class FixedWindowRateLimiterTest {

	@Test
	void rotatingClientsCannotGrowTheCountersBeyondTheBound() {
		MutableClock clock = new MutableClock();
		clock.freeze();
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(Duration.ofMinutes(1), 100, clock);

		for (int i = 0; i < 5_000; i++) {
			assertThat(limiter.acquire("GET|2001:db8::" + Integer.toHexString(i), 1).allowed()).isTrue();
		}

		assertThat(limiter.size()).isLessThanOrEqualTo(100);
	}

	@Test
	void countersAreDroppedWhenTheirWindowEnds() {
		MutableClock clock = new MutableClock();
		clock.freeze();
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(Duration.ofMinutes(1), 100, clock);
		limiter.acquire("GET|a", 1);
		assertThat(limiter.acquire("GET|a", 1).allowed()).isFalse();

		clock.advance(Duration.ofMinutes(1));

		assertThat(limiter.size()).isZero();
		assertThat(limiter.acquire("GET|a", 1).allowed()).as("a new window").isTrue();
	}

	@Test
	void anIpv6ClientIsCountedByItsSlash64() {
		assertThat(ClientIpResolver.limitKey("2001:db8:1:2:aaaa::1"))
			.isEqualTo(ClientIpResolver.limitKey("2001:db8:1:2:bbbb::9"))
			.isNotEqualTo(ClientIpResolver.limitKey("2001:db8:1:3::1"));
		assertThat(ClientIpResolver.limitKey("198.51.100.7")).isEqualTo("198.51.100.7");
	}

}
