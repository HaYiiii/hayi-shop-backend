package vn.hayi.shop.platform.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the client IP once per request, before any other filter, through the trusted proxies (Decision AU3, W9),
 * so sessions, security events, audit and the per-IP limits all see the same address through {@link ClientInfo#from}.
 */
final class ClientIpFilter extends OncePerRequestFilter {

	private final ClientIpResolver resolver;

	ClientIpFilter(ClientIpResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		request.setAttribute(ClientInfo.IP_ATTRIBUTE, this.resolver.resolve(request));
		chain.doFilter(request, response);
	}

}
