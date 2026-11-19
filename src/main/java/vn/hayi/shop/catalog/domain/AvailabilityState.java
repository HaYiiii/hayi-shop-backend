package vn.hayi.shop.catalog.domain;

/** Whether something is sold at a Branch (Catalog §5.6, Decision F); there is no generic "available". */
public enum AvailabilityState {

	NOT_AVAILABLE, AVAILABLE, SOLD_OUT, PAUSED,

	/** Derived only, never stored: outside the Product's selling hours at the time asked (Decision AK). */
	OUTSIDE_SELLING_HOURS;

	/** The states only the Sold-out workspace sets (Decision W2). */
	boolean isManualStop() {
		return this == SOLD_OUT || this == PAUSED;
	}

}
