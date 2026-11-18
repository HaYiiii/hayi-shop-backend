package vn.hayi.shop.catalog.domain;

/**
 * The state of a Product or Variant at a Branch with the sold-out / paused state it holds while off sale (Decision
 * AD5). A listing command only puts things on sale or takes them off (W2):
 * <ul>
 * <li>off sale ({@code NOT_AVAILABLE}): a {@code SOLD_OUT} / {@code PAUSED} state is held;</li>
 * <li>on sale ({@code AVAILABLE}) from off sale: back to the held state if any, else {@code AVAILABLE}; on sale while
 * {@code AVAILABLE}, {@code SOLD_OUT} or {@code PAUSED}: unchanged.</li>
 * </ul>
 */
record ListingAvailability(AvailabilityState state, AvailabilityState held) {

	ListingAvailability apply(AvailabilityState requested) {
		if (requested.isManualStop()) {
			throw new IllegalArgumentException("Only the Sold-out workspace sets " + requested);
		}
		if (requested == AvailabilityState.NOT_AVAILABLE) {
			return this.state.isManualStop() ? new ListingAvailability(AvailabilityState.NOT_AVAILABLE, this.state)
					: new ListingAvailability(AvailabilityState.NOT_AVAILABLE, this.held);
		}
		if (this.state == AvailabilityState.NOT_AVAILABLE) {
			return new ListingAvailability((this.held != null) ? this.held : AvailabilityState.AVAILABLE, null);
		}
		return this;
	}

}
