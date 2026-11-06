package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OptimisticLock;

import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.id.UuidV7;

/**
 * A brand-level merchandise item (Catalog §5.2): the aggregate of its Variants, which hold the prices. Starts
 * {@code DRAFT}; v1 sells only {@code STANDARD} (Decision B). Every sellable Product has at least one Variant and
 * exactly one non-archived default Variant.
 */
@Entity
@Table(name = "cat_product")
public class Product {

	@Id
	private UUID id;

	@Column(updatable = false)
	private String productCode;

	private String slug;

	private String name;

	private String shortDescription;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	private ProductType productType;

	@Enumerated(EnumType.STRING)
	private ProductStatus status;

	private UUID primaryCategoryId;

	private int sortOrder;

	private Instant archivedAt;

	@Column(updatable = false)
	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	/**
	 * Part of the aggregate (AGGREGATE-MAP §3.5); a page of Products loads its Variants in one batch. Kept out of
	 * Hibernate's automatic version bump (which also fires right after the first insert); every method that changes
	 * a Variant touches {@code updatedAt}, which moves the version.
	 */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	@OrderBy("sortOrder")
	@BatchSize(size = 100)
	private List<ProductVariant> variants = new ArrayList<>();

	protected Product() {
	}

	/**
	 * A new {@code DRAFT} Product.
	 * @param primaryCategory {@code null}, or an {@code ACTIVE} Category the caller has locked (Decision Z5)
	 * @throws DomainException {@code PRODUCT_TYPE_NOT_SUPPORTED} for {@code COMBO} (Decision B), or
	 * {@code CATALOG_CATEGORY_ARCHIVED}
	 * @throws IllegalArgumentException when the Variants break the invariants the web layer already checked: none,
	 * not exactly one default, a SKU twice, a negative price
	 */
	public static Product create(String productCode, String slug, String name, ProductType type,
			Category primaryCategory, String shortDescription, String description, List<NewVariant> variants,
			Instant now) {
		if (type != ProductType.STANDARD) {
			throw new DomainException(CatalogErrorCode.PRODUCT_TYPE_NOT_SUPPORTED,
					"Product type " + type + " is not supported yet");
		}
		requireValidVariants(variants);
		if (primaryCategory != null) {
			primaryCategory.requireActive();
		}
		Product product = new Product();
		product.id = UuidV7.generate();
		product.productCode = normalizeCode(productCode);
		product.slug = slug;
		product.name = name;
		product.shortDescription = shortDescription;
		product.description = description;
		product.productType = type;
		product.status = ProductStatus.DRAFT;
		product.primaryCategoryId = (primaryCategory != null) ? primaryCategory.getId() : null;
		product.createdAt = now;
		product.updatedAt = now;
		for (int i = 0; i < variants.size(); i++) {
			product.variants.add(ProductVariant.create(variants.get(i), i, now));
		}
		return product;
	}

	/** Codes and SKUs are case-insensitive identifiers, kept upper-case (Decision Z2). */
	public static String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private static void requireValidVariants(List<NewVariant> variants) {
		if (variants.isEmpty()) {
			throw new IllegalArgumentException("A Product needs at least one Variant");
		}
		if (variants.stream().filter(NewVariant::isDefault).count() != 1) {
			throw new IllegalArgumentException("A Product needs exactly one default Variant");
		}
		Set<String> skus = new HashSet<>();
		for (NewVariant variant : variants) {
			if (!skus.add(normalizeCode(variant.sku()))) {
				throw new IllegalArgumentException("SKU twice: " + variant.sku());
			}
		}
	}

	/**
	 * {@code DRAFT | INACTIVE → ACTIVE} when it can be sold: an {@code ACTIVE} Variant and an {@code ACTIVE} primary
	 * Category (Catalog §5.2, Decision AA1–AA2, W5). Returns the previous status.
	 * @param primaryCategory the primary Category, locked by the caller, or {@code null} when there is none
	 * @throws ProductActivationInvalidException listing every missing condition
	 */
	public ProductStatus activate(Category primaryCategory, Instant now) {
		requireStatus("activate", ProductStatus.DRAFT, ProductStatus.INACTIVE);
		List<ActivationIssue> issues = new ArrayList<>();
		if (this.variants.stream().noneMatch(variant -> variant.getStatus() == VariantStatus.ACTIVE)) {
			issues.add(ActivationIssue.NO_ACTIVE_VARIANT);
		}
		if (primaryCategory == null) {
			issues.add(ActivationIssue.PRIMARY_CATEGORY_MISSING);
		}
		else if (primaryCategory.getStatus() != CategoryStatus.ACTIVE) {
			issues.add(ActivationIssue.PRIMARY_CATEGORY_INACTIVE);
		}
		if (!issues.isEmpty()) {
			throw new ProductActivationInvalidException(this.id, issues);
		}
		return moveTo(ProductStatus.ACTIVE, now);
	}

	/** {@code ACTIVE → INACTIVE} (Decision AA1). Returns the previous status. */
	public ProductStatus deactivate(Instant now) {
		requireStatus("deactivate", ProductStatus.ACTIVE);
		return moveTo(ProductStatus.INACTIVE, now);
	}

	/**
	 * {@code DRAFT | ACTIVE | INACTIVE → ARCHIVED}, final; the row and its Variants stay (Catalog §5.9, Decision AA1).
	 * Returns the previous status.
	 */
	public ProductStatus archive(Instant now) {
		requireStatus("archive", ProductStatus.DRAFT, ProductStatus.ACTIVE, ProductStatus.INACTIVE);
		this.archivedAt = now;
		return moveTo(ProductStatus.ARCHIVED, now);
	}

	private void requireStatus(String action, ProductStatus... allowed) {
		if (!List.of(allowed).contains(this.status)) {
			throw new DomainException(CatalogErrorCode.CATALOG_PRODUCT_INVALID_TRANSITION,
					"Cannot " + action + " Product " + this.id + " in " + this.status, this.status.name());
		}
	}

	private ProductStatus moveTo(ProductStatus next, Instant now) {
		ProductStatus previous = this.status;
		this.status = next;
		this.updatedAt = now;
		return previous;
	}

	/** Lowest base price among non-archived Variants, the list's "from" price (Decision Z6). */
	public OptionalLong fromPrice() {
		return liveVariants().stream().mapToLong(ProductVariant::getBasePrice).min();
	}

	public List<ProductVariant> liveVariants() {
		return this.variants.stream().filter(variant -> variant.getStatus() != VariantStatus.ARCHIVED).toList();
	}

	public UUID getId() {
		return this.id;
	}

	public String getProductCode() {
		return this.productCode;
	}

	public String getSlug() {
		return this.slug;
	}

	public String getName() {
		return this.name;
	}

	public String getShortDescription() {
		return this.shortDescription;
	}

	public String getDescription() {
		return this.description;
	}

	public ProductType getProductType() {
		return this.productType;
	}

	public ProductStatus getStatus() {
		return this.status;
	}

	public UUID getPrimaryCategoryId() {
		return this.primaryCategoryId;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

	public Instant getArchivedAt() {
		return this.archivedAt;
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

	public List<ProductVariant> getVariants() {
		return List.copyOf(this.variants);
	}

}
