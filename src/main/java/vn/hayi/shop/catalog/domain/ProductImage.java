package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import vn.hayi.shop.shared.id.UuidV7;

/**
 * An image of a Product: a stored file plus what Catalog says about it (alt text, order, primary), Storage §18.3,
 * Decision AG. Part of the {@link Product} aggregate.
 */
@Entity
@Table(name = "cat_product_image")
public class ProductImage {

	@Id
	private UUID id;

	@Column(updatable = false)
	private UUID storedObjectId;

	private String altText;

	private int sortOrder;

	private boolean isPrimary;

	@Column(updatable = false)
	private Instant createdAt;

	protected ProductImage() {
	}

	static ProductImage create(UUID storedObjectId, String altText, int sortOrder, boolean isPrimary, Instant now) {
		ProductImage image = new ProductImage();
		image.id = UuidV7.generate();
		image.storedObjectId = storedObjectId;
		image.altText = altText;
		image.sortOrder = sortOrder;
		image.isPrimary = isPrimary;
		image.createdAt = now;
		return image;
	}

	void setAltText(String altText) {
		this.altText = altText;
	}

	void setPrimary(boolean isPrimary) {
		this.isPrimary = isPrimary;
	}

	void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getStoredObjectId() {
		return this.storedObjectId;
	}

	public String getAltText() {
		return this.altText;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

	public boolean isPrimary() {
		return this.isPrimary;
	}

}
