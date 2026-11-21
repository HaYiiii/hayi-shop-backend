package vn.hayi.shop.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ThrottledException;

/**
 * Per-IP login and per-session refresh limits on both surfaces (API 01-security §6.3, Decision K5, AO4). Counters
 * are in this instance's memory and bounded, like the public limit (W9). Over a limit → {@code 429 RATE_LIMITED} +
 * {@code Retry-After}, and one {@code RATE_LIMIT_EXCEEDED} event per key and window.
 */
@Component
@EnableConfigurationProperties(AuthRateLimiter.Properties.class)
public class AuthRateLimiter {

	private final Properties properties;

	private final FixedWindowRateLimiter limiter;

	private final SecurityEventSink events;

	AuthRateLimiter(Properties properties, SecurityEventSink events, Clock clock) {
		this.properties = properties;
		this.events = events;
		this.limiter = new FixedWindowRateLimiter(Duration.ofMinutes(1), properties.maxTracked(), clock);
	}

	/**
	 * @param loginPerMinute login attempts per IP per minute, counted per surface
	 * @param refreshPerMinute refreshes per session per minute
	 * @param maxTracked bound of the counters kept in memory
	 */
	@ConfigurationProperties("hayi.rate-limit.auth")
	public record Properties(@DefaultValue("true") boolean enabled, @DefaultValue("20") int loginPerMinute,
			@DefaultValue("30") int refreshPerMinute, @DefaultValue("100000") long maxTracked) {
	}

	/** Counts a login attempt from {@code client} on {@code surface}. */
	public void checkLogin(Surface surface, ClientInfo client) {
		if (client.ipAddress() == null) {
			return;
		}
		check("LOGIN|" + surface.name() + "|" + ClientIpResolver.limitKey(client.ipAddress()),
				this.properties.loginPerMinute(), client, Map.of("bucket", "LOGIN", "surface", surface.name()));
	}

	/** Counts a refresh of {@code sessionId}, the session its verified CSRF token belongs to. */
	public void checkRefresh(Surface surface, UUID sessionId, ClientInfo client) {
		if (sessionId == null) {
			return;
		}
		check("REFRESH|" + sessionId, this.properties.refreshPerMinute(), client,
				Map.of("bucket", "REFRESH", "surface", surface.name(), "sessionId", sessionId.toString()));
	}

	private void check(String key, int limit, ClientInfo client, Map<String, Object> metadata) {
		if (!this.properties.enabled()) {
			return;
		}
		FixedWindowRateLimiter.Decision decision = this.limiter.acquire(key, limit);
		if (decision.allowed()) {
			return;
		}
		if (decision.firstRefusal()) {
			this.events.rateLimitExceeded(client, metadata);
		}
		throw new ThrottledException(CommonErrorCode.RATE_LIMITED, "Rate limit exceeded: " + metadata.get("bucket"),
				decision.retryAfter());
	}

}
