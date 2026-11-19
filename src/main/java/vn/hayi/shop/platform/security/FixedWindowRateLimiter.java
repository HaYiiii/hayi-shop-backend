package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts requests per key in fixed windows that start at a key's first request (W9). Kept in memory of this
 * instance: with several instances each enforces the limit on its own share of the traffic.
 */
final class FixedWindowRateLimiter {

	/** Above this many keys, windows that have ended are dropped before a new one is added. */
	private static final int SWEEP_ABOVE = 10_000;

	private final Duration window;

	private final Clock clock;

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	FixedWindowRateLimiter(Duration window, Clock clock) {
		this.window = window;
		this.clock = clock;
	}

	/**
	 * Counts one request. {@code firstRefusal} is true once per key and window, so a flood logs one event.
	 */
	Decision acquire(String key, int limit) {
		Instant now = this.clock.instant();
		if (this.windows.size() > SWEEP_ABOVE) {
			this.windows.values().removeIf(existing -> !now.isBefore(existing.end));
		}
		Window current = this.windows.compute(key,
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
