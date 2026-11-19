package vn.hayi.shop.shared.time;

import java.time.LocalTime;
import java.util.Objects;

/**
 * {@code [start, end)} within one day in local time, never past midnight. Shared by Branch hours (Decision Q),
 * fulfillment windows (Decision S) and Product selling windows (Decision AK); each module keeps its own rules for
 * which ranges it accepts.
 */
public record LocalTimeRange(LocalTime start, LocalTime end) {

	public LocalTimeRange {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
	}

	/** {@code start < end}: a range never runs over midnight. */
	public boolean isOrdered() {
		return this.start.isBefore(this.end);
	}

	/** Touching ranges ({@code 09–12}, {@code 12–15}) do not overlap. */
	public boolean overlaps(LocalTimeRange other) {
		return this.start.isBefore(other.end) && other.start.isBefore(this.end);
	}

	public boolean contains(LocalTimeRange other) {
		return !this.start.isAfter(other.start) && !other.end.isAfter(this.end);
	}

	/** {@code start ≤ time < end}. */
	public boolean contains(LocalTime time) {
		return !time.isBefore(this.start) && time.isBefore(this.end);
	}

}
