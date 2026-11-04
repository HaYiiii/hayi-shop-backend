package vn.hayi.shop.fulfillment.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads the per-slot counters of {@code ful_slot_capacity}; claiming them comes with Create Order (S5). */
public interface SlotUsage {

	/** Counters of the Branch's slots from {@code from} to {@code to} (both included); slots without a row are absent. */
	Map<SlotKey, UsedCapacity> between(UUID branchId, FulfillmentType type, LocalDate from, LocalDate to);

	/** Counter rows from {@code from} on with at least one order or item claimed, by date then start. */
	List<ClaimedSlot> claimedFrom(UUID branchId, FulfillmentType type, LocalDate from);

}
