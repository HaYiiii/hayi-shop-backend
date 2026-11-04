package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.util.List;

/** One date's pickup hours as entered: closed, or its own windows sharing one capacity and reason (Decision S3). */
public record DateOverride(LocalDate date, boolean closed, List<TimeWindow> windows, Integer maxOrders,
		Integer maxItems, String reason) {

	public DateOverride {
		windows = List.copyOf(windows);
	}

}
