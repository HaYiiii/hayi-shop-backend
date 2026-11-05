package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A node of the menu taxonomy (Catalog §5.3): a root or the child of a root, never deeper (Decision Y2). A
 * configuration aggregate of its own (AGGREGATE-MAP); the parent is kept as an id. Starts {@code ACTIVE}; archiving is
 * final in MVP (Decision W5, Y4).
 */
@Entity
@Table(name = "cat_category")
public class Category {

	@Id
	private UUID id;

	private UUID parentId;

	private String slug;

	private String name;

	private String description;

	private int sortOrder;

	@Enumerated(EnumType.STRING)
	private CategoryStatus status;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	protected Category() {
	}

	/**
	 * @param parent the new Category's parent, already checked with {@link #requireCanParent}, or {@code null}
	 */
	public static Category create(String slug, String name, String description, Category parent, int sortOrder,
			Instant now) {
		Category category = new Category();
		category.id = UuidV7.generate();
		if (parent != null) {
			parent.requireCanParent(category.id);
			category.parentId = parent.id;
		}
		category.slug = slug;
		category.name = name;
		category.description = description;
		category.sortOrder = sortOrder;
		category.status = CategoryStatus.ACTIVE;
		category.createdAt = now;
		category.updatedAt = now;
		return category;
	}

	/**
	 * This Category can take {@code childId} as a child: it is an {@code ACTIVE} root other than the child itself.
	 * @throws DomainException {@code CATALOG_CATEGORY_ARCHIVED}, or {@code CATALOG_CATEGORY_DEPTH_EXCEEDED} when the
	 * tree would get a third level
	 */
	public void requireCanParent(UUID childId) {
		if (this.status == CategoryStatus.ARCHIVED) {
			throw new DomainException(CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED,
					"Category " + this.id + " is archived");
		}
		if (this.parentId != null || this.id.equals(childId)) {
			throw depthExceeded();
		}
	}

	/** Changes name, slug and description; returns what changed (nothing changed → version stays). */
	public FieldDiff edit(String newName, String newSlug, String newDescription, Instant now) {
		requireNotArchived();
		FieldDiff diff = new FieldDiff();
		this.name = diff.track("name", this.name, newName);
		this.slug = diff.track("slug", this.slug, newSlug);
		this.description = diff.track("description", this.description, newDescription);
		touchIf(diff, now);
		return diff;
	}

	/**
	 * Moves this Category under {@code parent} ({@code null} = make it a root), last among its new siblings
	 * ({@code sortOrder}). A Category with children, archived ones included, cannot become a child (Decision Y3).
	 */
	public void moveTo(Category parent, boolean hasActiveChildren, int sortOrder, FieldDiff diff, Instant now) {
		requireNotArchived();
		UUID newParentId = (parent != null) ? parent.id : null;
		if (Objects.equals(this.parentId, newParentId)) {
			return;
		}
		if (parent != null) {
			parent.requireCanParent(this.id);
			if (hasActiveChildren) {
				throw depthExceeded();
			}
		}
		this.parentId = diff.track("parentId", this.parentId, newParentId);
		this.sortOrder = sortOrder;
		touchIf(diff, now);
	}

	/**
	 * {@code ACTIVE → ARCHIVED} for good.
	 * @throws DomainException {@code CATALOG_CATEGORY_ARCHIVED} when already archived, {@code CATALOG_CATEGORY_IN_USE}
	 * while something not archived still uses it (Decision W5)
	 */
	public void archive(boolean inUse, Instant now) {
		requireNotArchived();
		if (inUse) {
			throw new DomainException(CatalogErrorCode.CATALOG_CATEGORY_IN_USE,
					"Category " + this.id + " still has non-archived children or Products");
		}
		this.status = CategoryStatus.ARCHIVED;
		this.updatedAt = now;
	}

	/** Places it among its siblings; returns whether the position changed (only then the version moves). */
	public boolean placeAt(int position, Instant now) {
		if (this.sortOrder == position) {
			return false;
		}
		this.sortOrder = position;
		this.updatedAt = now;
		return true;
	}

	/**
	 * Usable as a Product's primary Category (Decision W5, Z5).
	 * @throws DomainException {@code CATALOG_CATEGORY_ARCHIVED} unless {@code ACTIVE}
	 */
	public void requireActive() {
		if (this.status != CategoryStatus.ACTIVE) {
			throw new DomainException(CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED,
					"Category " + this.id + " is " + this.status);
		}
	}

	public void requireNotArchived() {
		if (this.status == CategoryStatus.ARCHIVED) {
			throw new DomainException(CatalogErrorCode.CATALOG_CATEGORY_ARCHIVED,
					"Category " + this.id + " is archived");
		}
	}

	private void touchIf(FieldDiff diff, Instant now) {
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
	}

	private DomainException depthExceeded() {
		return new DomainException(CatalogErrorCode.CATALOG_CATEGORY_DEPTH_EXCEEDED,
				"Category tree would exceed 2 levels at " + this.id);
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getParentId() {
		return this.parentId;
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

	public int getSortOrder() {
		return this.sortOrder;
	}

	public CategoryStatus getStatus() {
		return this.status;
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

}
