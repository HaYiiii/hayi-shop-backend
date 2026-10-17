package vn.hayi.shop.shared.error;

import java.time.Duration;
import java.util.Objects;

/**
 * Refused because of too many attempts; the client may retry after {@link #retryAfter()} (sent as
 * {@code Retry-After}, API README §9).
 */
public class ThrottledException extends DomainException {

	private final Duration retryAfter;

	public ThrottledException(ErrorCode code, String message, Duration retryAfter, Object... detailArgs) {
		super(code, message, detailArgs);
		this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
	}

	public Duration retryAfter() {
		return this.retryAfter;
	}

}
