package vn.hayi.shop.fulfillment.application;

import java.util.List;

/**
 * One fulfillment config as the Backoffice reads it; {@code version} 0 means not saved yet (Decision R1).
 * {@code overbookedSlots} is filled after a change only (Decision V2).
 */
public record FulfillmentConfigView(String fulfillmentType, FulfillmentRules rules, long version,
		List<OverbookedSlotView> overbookedSlots) {
}
