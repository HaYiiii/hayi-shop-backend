package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A curated, flat grouping of Products in order (Catalog §5.3, Decision AL1), a configuration aggregate of its own
 * (AGGREGATE-MAP). Products are kept as ids. Starts {@code ACTIVE}; {@code ARCHIVED} is final.
 */
@Entity
@Table(name = "cat_collection")
public class Collection {

	public static final int MAX_PRODUCTS = 200;

	@Id
	private UUID id;

	private String slug;

	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	private CollectionStatus status;

	private int sortOrder;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	/** In order; {@code sort_order} is the position. Replaced as a whole, so the rows are rewritten (no PK clash). */
	@ElementCollection
	@CollectionTable(name = "cat_collection_product", joinColumns = @JoinColumn(name = "collection_id"))
	@Column(name = "product_id")
	@OrderColumn(name = "sort_order")
	private List<UUID> productIds = new ArrayList<>();

	protected Collection() {
	}

	public static Collection create(String slug, String name, String description, int sortOrder, Instant now) {
		Collection collection = new Collection();
		collection.id = UuidV7.generate();
		collection.slug = slug;
		collection.name = name;
		collection.description = description;
		collection.status = CollectionStatus.ACTIVE;
		collection.sortOrder = sortOrder;
		collection.createdAt = now;
		collection.updatedAt = now;
		return collection;
	}

	/**
	 * Changes the fields and {@code ACTIVE ↔ INACTIVE}; returns what changed (nothing changed → version stays).
	 * @throws DomainException {@code CATALOG_COLLECTION_ARCHIVED}
	 */
	public FieldDiff edit(String newName, String newSlug, String newDescription, int newSortOrder,
			CollectionStatus newStatus, Instant now) {
		requireNotArchived();
		if (newStatus == CollectionStatus.ARCHIVED) {
			throw new IllegalArgumentException("Archive through archive()");
		}
		FieldDiff diff = new FieldDiff();
		this.name = diff.track("name", this.name, newName);
		this.slug = diff.track("slug", this.slug, newSlug);
		this.description = diff.track("description", this.description, newDescription);
		this.sortOrder = diff.track("sortOrder", this.sortOrder, newSortOrder);
		this.status = diff.track("status", this.status, newStatus);
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
		return diff;
	}

	/**
	 * Puts exactly these Products in this order; the caller checked they exist and are not archived. Returns whether
	 * anything changed.
	 * @throws DomainException {@code CATALOG_COLLECTION_ARCHIVED}
	 */
	public boolean replaceProducts(List<UUID> productIds, Instant now) {
		requireNotArchived();
		if (this.productIds.equals(productIds)) {
			return false;
		}
		this.productIds = new ArrayList<>(productIds);
		this.updatedAt = now;
		return true;
	}

	/** @throws DomainException {@code CATALOG_COLLECTION_ARCHIVED} when already archived */
	public void archive(Instant now) {
		requireNotArchived();
		this.status = CollectionStatus.ARCHIVED;
		this.updatedAt = now;
	}

	public void requireNotArchived() {
		if (this.status == CollectionStatus.ARCHIVED) {
			throw new DomainException(CatalogErrorCode.CATALOG_COLLECTION_ARCHIVED,
					"Collection " + this.id + " is archived");
		}
	}

	public UUID getId() {
		return this.id;
	}

	public String getSlug() {
		return this.slug;
	}

	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	public CollectionStatus getStatus() {
		return this.status;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public long getVersion() {
		return this.version;
	}

	public List<UUID> getProductIds() {
		return List.copyOf(this.productIds);
	}

}
