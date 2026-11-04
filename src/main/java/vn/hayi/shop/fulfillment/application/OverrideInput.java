package vn.hayi.shop.fulfillment.application;

import java.time.LocalDate;
import java.util.List;

/** A date's pickup hours as entered (API 09 §2.2): closed, or windows sharing one capacity and reason. */
public record OverrideInput(LocalDate date, boolean isClosed, List<PickupWindowInput> intervals, Integer maxOrders,
		Integer maxItems, String reason) {

	public OverrideInput {
		intervals = List.copyOf(intervals);
	}

}
