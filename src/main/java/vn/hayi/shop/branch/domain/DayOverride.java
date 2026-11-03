package vn.hayi.shop.branch.domain;

import java.time.LocalDate;
import java.util.List;

/** The hours of one date: closed all day, or its own ranges (ERD 03 {@code brn_business_hour_override}). */
public record DayOverride(LocalDate date, boolean closed, List<TimeRange> ranges, String reason) {

	public DayOverride {
		ranges = List.copyOf(ranges);
	}

}
