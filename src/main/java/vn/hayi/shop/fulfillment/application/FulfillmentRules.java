package vn.hayi.shop.fulfillment.application;

/** The editable values of a fulfillment config as entered and read (API 09 §2); checked by the domain (Decision R3). */
public record FulfillmentRules(boolean isEnabled, boolean asapEnabled, int maxAdvanceDays, int slotDurationMinutes,
		int sameDayCutoffMinutes, int minLeadMinutes, Integer groupOrderMinLeadMinutes,
		Integer customerCancelCutoffMinutes, Integer defaultSlotMaxOrders, Integer defaultSlotMaxItems,
		String pickupInstructions) {
}
