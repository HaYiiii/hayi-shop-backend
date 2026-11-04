package vn.hayi.shop.fulfillment.domain;

/** How a slot stands for a new order, in priority order (Decision T4). */
public enum SlotAvailability {

	CUTOFF_PASSED,
	FULL,
	NEARLY_FULL,
	AVAILABLE

}
