package vn.hayi.shop.fulfillment.domain;

import java.util.Optional;

/** How an Order is handed over (Fulfillment §8.2). The MVP takes {@code PICKUP} only. */
public enum FulfillmentType {

	PICKUP,
	DELIVERY,
	DINE_IN;

	public boolean supportedInMvp() {
		return this == PICKUP;
	}

	/** Exact, case-sensitive match of an API value. */
	public static Optional<FulfillmentType> parse(String value) {
		for (FulfillmentType type : values()) {
			if (type.name().equals(value)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

}
