package vn.hayi.shop.platform.observability;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id before anything else runs (including security): reuses the trace-id of a
 * valid W3C {@code traceparent} from the frontend, otherwise generates one. The id goes to the MDC and to the
 * {@code X-Trace-Id} response header (API README §9, Observability §19.3).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {

	/** W3C Trace Context: {@code version-traceid-parentid-flags}; version {@code ff} is forbidden. */
	private static final Pattern TRACEPARENT = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

	private static final String INVALID_VERSION = "ff";

	private static final String INVALID_TRACE_ID = "0".repeat(32);

	private static final String INVALID_PARENT_ID = "0".repeat(16);

	private final SecureRandom random = new SecureRandom();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = fromTraceparent(request.getHeader("traceparent"));
		if (traceId == null) {
			traceId = generate();
		}
		MDC.put(TraceIds.MDC_KEY, traceId);
		response.setHeader(TraceIds.RESPONSE_HEADER, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(TraceIds.MDC_KEY);
		}
	}

	private static String fromTraceparent(String traceparent) {
		if (traceparent == null) {
			return null;
		}
		Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
		if (!matcher.matches() || matcher.group(1).equals(INVALID_VERSION) || matcher.group(2).equals(INVALID_TRACE_ID)
				|| matcher.group(3).equals(INVALID_PARENT_ID)) {
			return null;
		}
		return matcher.group(2);
	}

	private String generate() {
		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

}
