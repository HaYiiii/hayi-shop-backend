package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OptimisticLock;
import org.springframework.data.domain.Persistable;

import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A Product sold at a Branch (Catalog §5.5–5.6, AGGREGATE-MAP §3.5, Decision F, AD2): its state there and the
 * Variant overrides. Kept apart from {@link Product} so Branch writes do not race admin edits. Listing commands only
 * put it on sale or take it off; {@code SOLD_OUT} / {@code PAUSED} belong to the Sold-out workspace (W2).
 */
@Entity
@Table(name = "cat_branch_product_listing")
public class BranchProductListing implements Persistable<UUID> {

	@Transient
	private boolean isNew;

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID branchId;

	@Column(updatable = false)
	private UUID productId;

	@Enumerated(EnumType.STRING)
	private AvailabilityState availabilityState;

	@Enumerated(EnumType.STRING)
	private AvailabilityState heldState;

	private Instant soldOutUntil;

	private String stateReason;

	private Instant stockShortageSince;

	private Instant stateChangedAt;

	private UUID stateChangedByUserId;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	/**
	 * Part of the aggregate; as for {@code Product.variants}, a change touches {@code updatedAt}, which moves the
	 * version.
	 */
	@ElementCollection
	@CollectionTable(name = "cat_branch_variant_listing", joinColumns = @JoinColumn(name = "listing_id"))
	@OptimisticLock(excluded = true)
	@BatchSize(size = 100)
	private List<BranchVariantListing> variants = new ArrayList<>();

	protected BranchProductListing() {
	}

	/**
	 * A new listing, off sale until {@link #setAvailability}; the caller has checked the Product is not archived
	 * (W3). A saved listing starts at version 1: version 0 is "not listed", which {@code "v0"} creates from (AD2, R2).
	 */
	public static BranchProductListing create(UUID branchId, UUID productId, Instant now) {
		BranchProductListing listing = new BranchProductListing();
		listing.isNew = true;
		listing.version = 1;
		listing.id = UuidV7.generate();
		listing.branchId = branchId;
		listing.productId = productId;
		listing.availabilityState = AvailabilityState.NOT_AVAILABLE;
		listing.createdAt = now;
		listing.updatedAt = now;
		return listing;
	}

	/**
	 * Puts the Product on sale ({@code AVAILABLE}) or takes it off ({@code NOT_AVAILABLE}) at this Branch, holding a
	 * {@code SOLD_OUT} / {@code PAUSED} state while off sale (Decision AD5). Returns what changed.
	 * @throws IllegalArgumentException for {@code SOLD_OUT} / {@code PAUSED}, which the web layer refuses (W2)
	 */
	public FieldDiff setAvailability(AvailabilityState requested, UUID actorUserId, Instant now) {
		ListingAvailability next = new ListingAvailability(this.availabilityState, this.heldState).apply(requested);
		FieldDiff diff = new FieldDiff();
		AvailabilityState state = diff.track("availabilityState", this.availabilityState, next.state());
		if (state != this.availabilityState) {
			this.stateChangedAt = now;
			this.stateChangedByUserId = actorUserId;
		}
		if (state != this.availabilityState || next.held() != this.heldState) {
			this.availabilityState = state;
			this.heldState = next.held();
			this.updatedAt = now;
		}
		return diff;
	}

	/**
	 * Sets a Variant's full Branch price ({@code null} = base price) and puts it on sale or takes it off (Decision
	 * AD2, AD4, AD5); the price never touches its state (W2). Returns what changed.
	 * @param variantId a non-archived Variant of this Product, checked by the caller
	 */
	public FieldDiff putVariant(UUID variantId, Long price, AvailabilityState requested, UUID actorUserId,
			Instant now) {
		if (price != null && price < 0) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE,
					List.of(new RuleViolationsException.Violation("price", "MIN")));
		}
		int index = indexOf(variantId);
		BranchVariantListing current = (index >= 0) ? this.variants.get(index)
				: BranchVariantListing.inherited(variantId, now);
		ListingAvailability next = current.availability().apply(requested);
		FieldDiff diff = new FieldDiff();
		diff.track("price", current.price(), price);
		diff.track("availabilityState", current.availabilityState(), next.state());
		if (diff.isEmpty() && next.held() == current.heldState()) {
			return diff;
		}
		BranchVariantListing updated = current.with(price, next, actorUserId, now);
		if (index >= 0) {
			this.variants.set(index, updated);
		}
		else {
			this.variants.add(updated);
		}
		this.updatedAt = now;
		return diff;
	}

	/** The override of this Variant, if it has one. */
	public Optional<BranchVariantListing> variant(UUID variantId) {
		int index = indexOf(variantId);
		return (index >= 0) ? Optional.of(this.variants.get(index)) : Optional.empty();
	}

	private int indexOf(UUID variantId) {
		for (int i = 0; i < this.variants.size(); i++) {
			if (this.variants.get(i).variantId().equals(variantId)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public UUID getId() {
		return this.id;
	}

	/** Lets Spring Data {@code persist} a new listing with its preset version instead of merging it. */
	@Override
	public boolean isNew() {
		return this.isNew;
	}

	@PostPersist
	@PostLoad
	void markPersisted() {
		this.isNew = false;
	}

	public UUID getBranchId() {
		return this.branchId;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public AvailabilityState getAvailabilityState() {
		return this.availabilityState;
	}

	public long getVersion() {
		return this.version;
	}

}
