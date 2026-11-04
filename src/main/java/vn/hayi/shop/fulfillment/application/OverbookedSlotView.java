package vn.hayi.shop.fulfillment.application;

import java.time.LocalDate;
import java.time.LocalTime;

/** A slot whose claims exceed its capacity after a change; max {@code 0} when it is no longer offered (Decision V2). */
public record OverbookedSlotView(LocalDate date, LocalTime start, LocalTime end, int usedOrders, Integer maxOrders,
		int usedItems, Integer maxItems) {
}
