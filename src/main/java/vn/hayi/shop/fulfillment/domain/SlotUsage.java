package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Reads the per-slot counters of {@code ful_slot_capacity}; claiming them comes with Create Order (S5). */
public interface SlotUsage {

	/** Counters of the Branch's slots from {@code from} to {@code to} (both included); slots without a row are absent. */
	Map<SlotKey, UsedCapacity> between(UUID branchId, FulfillmentType type, LocalDate from, LocalDate to);

}
