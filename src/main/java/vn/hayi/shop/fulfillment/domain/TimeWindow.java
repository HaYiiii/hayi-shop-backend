package vn.hayi.shop.fulfillment.domain;

import java.time.LocalTime;
import java.util.Objects;

import vn.hayi.shop.shared.time.LocalTimeRange;

/** {@code [start, end)} within one day, Branch local time. */
public record TimeWindow(LocalTime start, LocalTime end) {

	public TimeWindow {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
	}

	boolean isOrdered() {
		return range().isOrdered();
	}

	/** Touching windows ({@code 09–12}, {@code 12–15}) do not overlap (Decision S6). */
	boolean overlaps(TimeWindow other) {
		return range().overlaps(other.range());
	}

	boolean contains(TimeWindow other) {
		return range().contains(other.range());
	}

	LocalTimeRange range() {
		return new LocalTimeRange(this.start, this.end);
	}

}
