package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * A sellable form of a Product (size M, L…) holding the full base price (Catalog §5.2). Part of the {@link Product}
 * aggregate: changed only through it, so a change bumps the Product's version.
 */
@Entity
@Table(name = "cat_product_variant")
public class ProductVariant {

	@Id
	private UUID id;

	@Column(updatable = false)
	private String sku;

	private String name;

	private long basePrice;

	private boolean isDefault;

	@Enumerated(EnumType.STRING)
	private VariantStatus status;

	private int sortOrder;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	protected ProductVariant() {
	}

	static ProductVariant create(NewVariant variant, int sortOrder, Instant now) {
		if (variant.basePrice() < 0) {
			throw new IllegalArgumentException("basePrice must not be negative: " + variant.basePrice());
		}
		ProductVariant created = new ProductVariant();
		created.id = UuidV7.generate();
		created.sku = Product.normalizeCode(variant.sku());
		created.name = variant.name();
		created.basePrice = variant.basePrice();
		created.isDefault = variant.isDefault();
		created.status = VariantStatus.ACTIVE;
		created.sortOrder = sortOrder;
		created.createdAt = now;
		created.updatedAt = now;
		return created;
	}

	public UUID getId() {
		return this.id;
	}

	public String getSku() {
		return this.sku;
	}

	public String getName() {
		return this.name;
	}

	public long getBasePrice() {
		return this.basePrice;
	}

	public boolean isDefault() {
		return this.isDefault;
	}

	public VariantStatus getStatus() {
		return this.status;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

}
