package vn.hayi.shop.fulfillment.api;

import java.time.LocalDate;
import java.util.List;

/** One date; {@code open} is {@code false} exactly when it has no slots (Decision T3). */
public record SlotDay(LocalDate date, boolean open, List<PickupSlot> slots) {

	public SlotDay {
		slots = List.copyOf(slots);
	}

}
