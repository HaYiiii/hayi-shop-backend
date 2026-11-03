package vn.hayi.shop.branch.api;

import java.time.LocalTime;
import java.util.Objects;

/** A Branch is open from {@code open} (inclusive) to {@code close} (exclusive), same day, Branch local time. */
public record OpeningInterval(LocalTime open, LocalTime close) {

	public OpeningInterval {
		Objects.requireNonNull(open, "open");
		Objects.requireNonNull(close, "close");
	}

}
