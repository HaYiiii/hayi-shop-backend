package vn.hayi.shop.platform.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The client IP behind the configured reverse proxies: {@code X-Forwarded-For} is read only when the peer is a trusted
 * proxy, from the right, skipping trusted hops, so a client cannot choose its own address (W9, ticket BE #60).
 */
public final class ClientIpResolver {

	private final List<IpAddressMatcher> trusted;

	public ClientIpResolver(List<String> trustedProxies) {
		this.trusted = trustedProxies.stream().map(String::trim).filter(value -> !value.isEmpty())
			.map(IpAddressMatcher::new).toList();
	}

	public String resolve(HttpServletRequest request) {
		String peer = request.getRemoteAddr();
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded == null || !isTrusted(peer)) {
			return peer;
		}
		String[] hops = forwarded.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (hop.isEmpty()) {
				continue;
			}
			if (!isTrusted(hop)) {
				return hop;
			}
		}
		return peer;
	}

	private boolean isTrusted(String address) {
		try {
			return this.trusted.stream().anyMatch(matcher -> matcher.matches(address));
		}
		catch (IllegalArgumentException ex) {
			// Not an IP literal (a forged or garbled header value): never trusted.
			return false;
		}
	}

}
