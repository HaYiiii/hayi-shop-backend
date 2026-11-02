package vn.hayi.shop.branch.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Latitude/longitude of a Branch, stored as {@code DECIMAL(9,6)}. */
public record GeoPoint(BigDecimal lat, BigDecimal lng) {

	public GeoPoint {
		Objects.requireNonNull(lat, "lat");
		Objects.requireNonNull(lng, "lng");
	}

}
