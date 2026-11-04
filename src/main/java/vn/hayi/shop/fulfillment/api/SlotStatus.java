package vn.hayi.shop.fulfillment.api;

/** Whether a slot can still be chosen (API 02 §3, Decision T4); {@code CLOSED} is reserved, not emitted in the MVP. */
public enum SlotStatus {

	AVAILABLE,
	NEARLY_FULL,
	FULL,
	CUTOFF_PASSED,
	CLOSED

}
