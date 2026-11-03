package vn.hayi.shop.branch.domain;

import java.time.LocalTime;
import java.util.Objects;

/** {@code [open, close)} within one day; {@link BusinessHoursRules} decides whether a range is acceptable. */
public record TimeRange(LocalTime open, LocalTime close) {

	public TimeRange {
		Objects.requireNonNull(open, "open");
		Objects.requireNonNull(close, "close");
	}

	boolean isOrdered() {
		return this.open.isBefore(this.close);
	}

	/** Touching ranges ({@code 09–12}, {@code 12–15}) do not overlap (Decision Q2). */
	boolean overlaps(TimeRange other) {
		return this.open.isBefore(other.close) && other.open.isBefore(this.close);
	}

}
