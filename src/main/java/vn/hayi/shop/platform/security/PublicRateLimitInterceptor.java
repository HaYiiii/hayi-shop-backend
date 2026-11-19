package vn.hayi.shop.platform.security;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.ThrottledException;

/**
 * Per-IP limit of {@code /api/public/v1} (Decision W9): {@code GET} in one budget, {@code POST …/catalog/resolve} in
 * another; other methods are not limited here. Over the limit → {@code 429 RATE_LIMITED} + {@code Retry-After}, and
 * one {@code RATE_LIMIT_EXCEEDED} security event per IP and window.
 */
final class PublicRateLimitInterceptor implements HandlerInterceptor {

	private final PublicRateLimitProperties properties;

	private final ClientIpResolver ips;

	private final FixedWindowRateLimiter limiter;

	private final SecurityEventSink events;

	PublicRateLimitInterceptor(PublicRateLimitProperties properties, ClientIpResolver ips,
			FixedWindowRateLimiter limiter, SecurityEventSink events) {
		this.properties = properties;
		this.ips = ips;
		this.limiter = limiter;
		this.events = events;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!this.properties.enabled()) {
			return true;
		}
		String bucket;
		int limit;
		if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
			bucket = "GET";
			limit = this.properties.getPerMinute();
		}
		else if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/catalog/resolve")) {
			bucket = "RESOLVE";
			limit = this.properties.resolvePerMinute();
		}
		else {
			return true;
		}
		String ip = this.ips.resolve(request);
		FixedWindowRateLimiter.Decision decision = this.limiter.acquire(bucket + "|" + ip, limit);
		if (decision.allowed()) {
			return true;
		}
		if (decision.firstRefusal()) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("bucket", bucket);
			metadata.put("limit", limit);
			metadata.put("path", request.getRequestURI());
			this.events.rateLimitExceeded(new ClientInfo(ip, request.getHeader("User-Agent")), metadata);
		}
		throw new ThrottledException(CommonErrorCode.RATE_LIMITED, "Rate limit exceeded for " + bucket,
				decision.retryAfter());
	}

}
