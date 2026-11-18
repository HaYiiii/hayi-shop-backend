package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.Persistable;

import vn.hayi.shop.shared.error.RuleViolationsException;

/**
 * An option's override at a Branch, for every Product that offers it there (Catalog §5.6, Decision F, AD3): its full
 * Branch extra price ({@code null} = Product override / library price). Its state changes only in the Sold-out
 * workspace.
 */
@Entity
@Table(name = "cat_branch_option_item_listing")
public class BranchOptionItemListing implements Persistable<BranchOptionItemListing.Key> {

	@Transient
	private boolean isNew;

	@EmbeddedId
	private Key id;

	private Long extraPrice;

	@Enumerated(EnumType.STRING)
	private AvailabilityState availabilityState;

	private Instant updatedAt;

	@Version
	private long version;

	protected BranchOptionItemListing() {
	}

	/**
	 * No row yet: inherits ({@code AVAILABLE}, no Branch price). A saved row starts at version 1: version 0 is "no
	 * row", which {@code "v0"} creates from (AD3, R2).
	 */
	public static BranchOptionItemListing create(UUID branchId, UUID optionItemId, Instant now) {
		BranchOptionItemListing listing = new BranchOptionItemListing();
		listing.isNew = true;
		listing.version = 1;
		listing.id = new Key(branchId, optionItemId);
		listing.availabilityState = AvailabilityState.AVAILABLE;
		listing.updatedAt = now;
		return listing;
	}

	/**
	 * Sets the Branch extra price (Decision AD3, AD4); returns what changed.
	 * @throws RuleViolationsException {@code CATALOG_PRICE_NEGATIVE} on {@code extraPrice}
	 */
	public FieldDiff setExtraPrice(Long price, Instant now) {
		if (price != null && price < 0) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE,
					List.of(new RuleViolationsException.Violation("extraPrice", "MIN")));
		}
		FieldDiff diff = new FieldDiff();
		Long next = diff.track("extraPrice", this.extraPrice, price);
		if (!diff.isEmpty()) {
			this.extraPrice = next;
			this.updatedAt = now;
		}
		return diff;
	}

	@Override
	public Key getId() {
		return this.id;
	}

	/** Lets Spring Data {@code persist} a new row with its preset version instead of merging it. */
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
		return this.id.branchId();
	}

	public UUID getOptionItemId() {
		return this.id.optionItemId();
	}

	public Long getExtraPrice() {
		return this.extraPrice;
	}

	public AvailabilityState getAvailabilityState() {
		return this.availabilityState;
	}

	public long getVersion() {
		return this.version;
	}

	/** Primary key {@code (branch_id, option_item_id)}. */
	@Embeddable
	public record Key(UUID branchId, UUID optionItemId) {
	}

}
