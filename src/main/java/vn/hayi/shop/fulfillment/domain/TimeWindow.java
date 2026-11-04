package vn.hayi.shop.fulfillment.domain;

import java.time.LocalTime;
import java.util.Objects;

/** {@code [start, end)} within one day, Branch local time. */
public record TimeWindow(LocalTime start, LocalTime end) {

	public TimeWindow {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
	}

	boolean isOrdered() {
		return this.start.isBefore(this.end);
	}

	/** Touching windows ({@code 09–12}, {@code 12–15}) do not overlap (Decision S6). */
	boolean overlaps(TimeWindow other) {
		return this.start.isBefore(other.end) && other.start.isBefore(this.end);
	}

	boolean contains(TimeWindow other) {
		return !this.start.isAfter(other.start) && !other.end.isAfter(this.end);
	}

}
