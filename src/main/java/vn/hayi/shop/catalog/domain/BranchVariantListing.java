package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * A Variant's override at a Branch (Catalog §5.5–5.6, ERD 05 {@code cat_branch_variant_listing}): its full Branch
 * price ({@code null} = base price) and state. A Variant without one inherits ({@code AVAILABLE}, base price).
 */
@Embeddable
public record BranchVariantListing(UUID variantId, Long price, @Enumerated(EnumType.STRING) AvailabilityState availabilityState,
		@Enumerated(EnumType.STRING) AvailabilityState heldState, Instant soldOutUntil, String stateReason,
		Instant stockShortageSince, Instant stateChangedAt, UUID stateChangedByUserId, Instant updatedAt) {

	static BranchVariantListing inherited(UUID variantId, Instant now) {
		return new BranchVariantListing(variantId, null, AvailabilityState.AVAILABLE, null, null, null, null, null, null,
				now);
	}

	BranchVariantListing with(Long newPrice, ListingAvailability availability, UUID actorUserId, Instant now) {
		boolean stateChanged = availability.state() != this.availabilityState;
		return new BranchVariantListing(this.variantId, newPrice, availability.state(), availability.held(),
				this.soldOutUntil, this.stateReason, this.stockShortageSince, stateChanged ? now : this.stateChangedAt,
				stateChanged ? actorUserId : this.stateChangedByUserId, now);
	}

	ListingAvailability availability() {
		return new ListingAvailability(this.availabilityState, this.heldState);
	}

}
