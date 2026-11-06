package vn.hayi.shop.catalog.domain;

import java.util.UUID;

import jakarta.persistence.Embeddable;

/**
 * An option group attached to a Product, in its place, with optional min/max overrides (ERD 05
 * {@code cat_product_option_group}). Part of the {@link Product} aggregate (Decision AC1).
 */
@Embeddable
public record ProductOptionGroupConfig(UUID id, UUID optionGroupId, Integer minSelectOverride,
		Integer maxSelectOverride, int sortOrder) {

	/** Same choice, ignoring the row id. */
	boolean sameChoiceAs(ProductOptionGroupConfig other) {
		return this.optionGroupId.equals(other.optionGroupId)
				&& java.util.Objects.equals(this.minSelectOverride, other.minSelectOverride)
				&& java.util.Objects.equals(this.maxSelectOverride, other.maxSelectOverride)
				&& this.sortOrder == other.sortOrder;
	}

	int effectiveMin(OptionGroup group) {
		return (this.minSelectOverride != null) ? this.minSelectOverride : group.getMinSelect();
	}

	int effectiveMax(OptionGroup group) {
		return (this.maxSelectOverride != null) ? this.maxSelectOverride : group.getMaxSelect();
	}

}
