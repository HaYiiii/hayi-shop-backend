package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * Counts requests per key in fixed windows that start at a key's first request (W9). The counters live in this
 * instance's memory, in a cache bounded by {@code maxKeys} whose entries expire when their window ends, so rotating
 * source addresses cannot grow it without limit. With several instances each enforces the limit on its own share of
 * the traffic.
 */
final class FixedWindowRateLimiter {

	private final Duration window;

	private final Clock clock;

	private final Cache<String, Window> windows;

	FixedWindowRateLimiter(Duration window, long maxKeys, Clock clock) {
		this.window = window;
		this.clock = clock;
		this.windows = Caffeine.newBuilder()
			.maximumSize(maxKeys)
			.ticker(() -> {
				Instant now = clock.instant();
				return TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
			})
			.expireAfter(new Expiry<String, Window>() {
				@Override
				public long expireAfterCreate(String key, Window value, long currentTime) {
					return Math.max(0, Duration.between(clock.instant(), value.end).toNanos());
				}

				@Override
				public long expireAfterUpdate(String key, Window value, long currentTime, long currentDuration) {
					return expireAfterCreate(key, value, currentTime);
				}

				@Override
				public long expireAfterRead(String key, Window value, long currentTime, long currentDuration) {
					return currentDuration;
				}
			})
			.build();
	}

	/**
	 * Counts one request. {@code firstRefusal} is true once per key and window, so a flood logs one event.
	 */
	Decision acquire(String key, int limit) {
		Instant now = this.clock.instant();
		ConcurrentMap<String, Window> map = this.windows.asMap();
		Window current = map.compute(key,
				(k, existing) -> (existing == null || !now.isBefore(existing.end)) ? new Window(now.plus(this.window))
						: existing);
		synchronized (current) {
			current.count++;
			if (current.count <= limit) {
				return new Decision(true, Duration.ZERO, false);
			}
			boolean first = !current.refused;
			current.refused = true;
			return new Decision(false, Duration.between(now, current.end), first);
		}
	}

	/** Keys held now, after pending evictions (tests). */
	long size() {
		this.windows.cleanUp();
		return this.windows.estimatedSize();
	}

	record Decision(boolean allowed, Duration retryAfter, boolean firstRefusal) {
	}

	private static final class Window {

		private final Instant end;

		private int count;

		private boolean refused;

		private Window(Instant end) {
			this.end = end;
		}

	}

}
