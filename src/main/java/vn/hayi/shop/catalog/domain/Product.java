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
