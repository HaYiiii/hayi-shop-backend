package vn.hayi.shop.platform.observability;

import org.slf4j.MDC;

/**
 * Access to the current request's trace id (Observability §19.3).
 */
public final class TraceIds {

	/** MDC key; structured JSON logs expose it as the {@code traceId} field. */
	public static final String MDC_KEY = "traceId";

	public static final String RESPONSE_HEADER = "X-Trace-Id";

	private TraceIds() {
	}

	public static String current() {
		return MDC.get(MDC_KEY);
	}

}
