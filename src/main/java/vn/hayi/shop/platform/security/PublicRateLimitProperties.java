package vn.hayi.shop.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-IP limits of {@code /api/public/v1} (Decision W9, API 01-security §6.3).
 *
 * @param enabled off only for local tools; on in every environment
 * @param getPerMinute {@code GET} requests per IP per minute
 * @param resolvePerMinute {@code POST …/catalog/resolve} requests per IP per minute
 * @param trustedProxies IPs or CIDR ranges of reverse proxies whose {@code X-Forwarded-For} is believed; empty (the
 * default) = the peer address is the client, the header is ignored
 * @param maxTrackedClients bound of the counters kept in memory; above it the least recently used are dropped
 */
@ConfigurationProperties("hayi.rate-limit.public")
public record PublicRateLimitProperties(@DefaultValue("true") boolean enabled, @DefaultValue("120") int getPerMinute,
		@DefaultValue("30") int resolvePerMinute, @DefaultValue List<String> trustedProxies,
		@DefaultValue("100000") long maxTrackedClients) {
}
