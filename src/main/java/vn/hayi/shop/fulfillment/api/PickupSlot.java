package vn.hayi.shop.fulfillment.api;

import java.time.LocalTime;

/**
 * One slot, Branch local time. The capacity numbers are for Create Order (S5) only: the public API shows the status
 * alone (Decision T5, T6). {@code null} max means no limit.
 */
public record PickupSlot(LocalTime start, LocalTime end, SlotStatus status, Integer maxOrders, Integer maxItems,
		int usedOrders, int usedItems) {
}
