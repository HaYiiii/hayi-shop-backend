package vn.hayi.shop.fulfillment.api;

import java.util.List;
import java.util.UUID;

/** The PICKUP slots of one Branch over consecutive dates. */
public record SlotCalendar(UUID branchId, List<SlotDay> days) {

	public SlotCalendar {
		days = List.copyOf(days);
	}

}
