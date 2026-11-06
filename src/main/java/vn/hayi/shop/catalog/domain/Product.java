package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import vn.hayi.shop.shared.error.RuleViolationsException;
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

	/** Attached option groups in order (Decision AC2); as for Variants, a change touches {@code updatedAt}. */
	@ElementCollection
	@CollectionTable(name = "cat_product_option_group", joinColumns = @JoinColumn(name = "product_id"))
	@OrderBy("sortOrder")
	@OptimisticLock(excluded = true)
	@BatchSize(size = 100)
	private List<ProductOptionGroupConfig> optionGroups = new ArrayList<>();

	/** Price overrides and hidden items of the attached groups (Decision AC3). */
	@ElementCollection
	@CollectionTable(name = "cat_product_option_item_override", joinColumns = @JoinColumn(name = "product_id"))
	@OptimisticLock(excluded = true)
	@BatchSize(size = 100)
	private Set<ProductOptionItemOverride> optionItemOverrides = new HashSet<>();

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
		List<RuleViolationsException.Violation> negative = new ArrayList<>();
		for (int i = 0; i < variants.size(); i++) {
			if (variants.get(i).basePrice() < 0) {
				negative.add(new RuleViolationsException.Violation("variants[" + i + "].basePrice", "MIN"));
			}
		}
		if (!negative.isEmpty()) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE, negative);
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
	 * @param attachedGroups the option groups attached to this Product, by id
	 * @throws ProductActivationInvalidException listing every missing condition
	 */
	public ProductStatus activate(Category primaryCategory, Map<UUID, OptionGroup> attachedGroups, Instant now) {
		requireStatus("activate", ProductStatus.DRAFT, ProductStatus.INACTIVE);
		List<ProductActivationInvalidException.Issue> issues = new ArrayList<>();
		if (this.variants.stream().noneMatch(variant -> variant.getStatus() == VariantStatus.ACTIVE)) {
			issues.add(ProductActivationInvalidException.Issue.of(ActivationIssue.NO_ACTIVE_VARIANT));
		}
		if (primaryCategory == null) {
			issues.add(ProductActivationInvalidException.Issue.of(ActivationIssue.PRIMARY_CATEGORY_MISSING));
		}
		else if (primaryCategory.getStatus() != CategoryStatus.ACTIVE) {
			// A guard: no endpoint sets a Category INACTIVE, and archiving a Category is refused while a non-archived
			// Product uses it (W5), so only data changed outside the API reaches this.
			issues.add(ProductActivationInvalidException.Issue.of(ActivationIssue.PRIMARY_CATEGORY_INACTIVE));
		}
		Set<UUID> hidden = this.optionItemOverrides.stream()
			.filter(ProductOptionItemOverride::isHidden)
			.map(ProductOptionItemOverride::optionItemId)
			.collect(Collectors.toSet());
		for (ProductOptionGroupConfig config : this.optionGroups) {
			if (!validForSale(config, attachedGroups.get(config.optionGroupId()), hidden)) {
				issues.add(new ProductActivationInvalidException.Issue(ActivationIssue.OPTION_CONFIG_INVALID,
						config.optionGroupId()));
			}
		}
		if (!issues.isEmpty()) {
			throw new ProductActivationInvalidException(this.id, issues);
		}
		return moveTo(ProductStatus.ACTIVE, now);
	}

	/** W4: the group is {@code ACTIVE} and {@code min ≤ max ≤} its visible {@code ACTIVE} items (Decision AC4). */
	private static boolean validForSale(ProductOptionGroupConfig config, OptionGroup group, Set<UUID> hidden) {
		if (group == null || group.getStatus() != OptionStatus.ACTIVE) {
			return false;
		}
		int min = config.effectiveMin(group);
		int max = config.effectiveMax(group);
		return min <= max && max <= group.visibleActiveItems(hidden);
	}

	/**
	 * Replaces the attached option groups, in order (Decision AC2). Returns whether anything changed; overrides of
	 * items whose group is no longer attached are dropped.
	 * @param groups every group named in {@code choices}, found by the caller
	 * @throws DomainException {@code CATALOG_PRODUCT_INVALID_TRANSITION} when archived, {@code CATALOG_OPTION_ARCHIVED},
	 * {@code OPTION_GROUP_MINMAX_INVALID}
	 */
	public boolean replaceOptionGroups(List<OptionGroupChoice> choices, Map<UUID, OptionGroup> groups,
			Instant now) {
		requireNotArchived();
		List<ProductOptionGroupConfig> next = new ArrayList<>();
		for (int i = 0; i < choices.size(); i++) {
			OptionGroupChoice choice = choices.get(i);
			OptionGroup group = groups.get(choice.optionGroupId());
			if (group.getStatus() != OptionStatus.ACTIVE) {
				throw new DomainException(CatalogErrorCode.CATALOG_OPTION_ARCHIVED,
						"Option group " + group.getId() + " is archived");
			}
			ProductOptionGroupConfig config = new ProductOptionGroupConfig(existingConfigId(choice.optionGroupId()),
					choice.optionGroupId(), choice.minSelectOverride(), choice.maxSelectOverride(), i);
			OptionGroup.requireValidLimits(group.getSelectionType(), config.effectiveMin(group),
					config.effectiveMax(group));
			next.add(config);
		}
		if (sameChoices(this.optionGroups, next)) {
			return false;
		}
		this.optionGroups.clear();
		this.optionGroups.addAll(next);
		Set<UUID> attachedItems = itemsOf(groups.values());
		this.optionItemOverrides.removeIf(override -> !attachedItems.contains(override.optionItemId()));
		this.updatedAt = now;
		return true;
	}

	/**
	 * Replaces the item overrides (Decision AC3). Returns whether anything changed.
	 * @param attachedGroups the groups attached to this Product
	 * @throws DomainException {@code OPTION_ITEM_NOT_ATTACHED}; {@code CATALOG_PRICE_NEGATIVE} with {@code errors[]}
	 */
	public boolean replaceItemOverrides(List<ProductOptionItemOverride> overrides,
			Map<UUID, OptionGroup> attachedGroups, Instant now) {
		requireNotArchived();
		List<RuleViolationsException.Violation> negative = new ArrayList<>();
		for (int i = 0; i < overrides.size(); i++) {
			Long price = overrides.get(i).extraPriceOverride();
			if (price != null && price < 0) {
				negative.add(new RuleViolationsException.Violation("overrides[" + i + "].extraPriceOverride", "MIN"));
			}
		}
		if (!negative.isEmpty()) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE, negative);
		}
		Set<UUID> attachedItems = itemsOf(attachedGroups.values());
		for (ProductOptionItemOverride override : overrides) {
			if (!attachedItems.contains(override.optionItemId())) {
				throw new DomainException(CatalogErrorCode.OPTION_ITEM_NOT_ATTACHED,
						"Option item " + override.optionItemId() + " is not in a group of Product " + this.id);
			}
		}
		Set<ProductOptionItemOverride> next = new HashSet<>(overrides);
		if (next.equals(this.optionItemOverrides)) {
			return false;
		}
		this.optionItemOverrides.clear();
		this.optionItemOverrides.addAll(next);
		this.updatedAt = now;
		return true;
	}

	/** Whether {@code overrides} would change the override price of some item (Decision AC3). */
	public boolean changesOverridePrices(List<ProductOptionItemOverride> overrides) {
		Map<UUID, Long> current = new HashMap<>();
		this.optionItemOverrides
			.forEach(override -> current.put(override.optionItemId(), override.extraPriceOverride()));
		Set<UUID> kept = new HashSet<>();
		for (ProductOptionItemOverride override : overrides) {
			kept.add(override.optionItemId());
			if (!Objects.equals(current.get(override.optionItemId()), override.extraPriceOverride())) {
				return true;
			}
		}
		return current.entrySet()
			.stream()
			.anyMatch(entry -> !kept.contains(entry.getKey()) && entry.getValue() != null);
	}

	private UUID existingConfigId(UUID groupId) {
		return this.optionGroups.stream()
			.filter(config -> config.optionGroupId().equals(groupId))
			.map(ProductOptionGroupConfig::id)
			.findFirst()
			.orElseGet(UuidV7::generate);
	}

	private static boolean sameChoices(List<ProductOptionGroupConfig> current, List<ProductOptionGroupConfig> next) {
		if (current.size() != next.size()) {
			return false;
		}
		for (int i = 0; i < current.size(); i++) {
			if (!current.get(i).sameChoiceAs(next.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static Set<UUID> itemsOf(Collection<OptionGroup> groups) {
		return groups.stream()
			.flatMap(group -> group.getItems().stream())
			.map(OptionItem::getId)
			.collect(Collectors.toSet());
	}

	private void requireNotArchived() {
		requireStatus("configure the options of", ProductStatus.DRAFT, ProductStatus.ACTIVE, ProductStatus.INACTIVE);
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

	public List<ProductOptionGroupConfig> getOptionGroups() {
		return List.copyOf(this.optionGroups);
	}

	public Set<ProductOptionItemOverride> getOptionItemOverrides() {
		return Set.copyOf(this.optionItemOverrides);
	}

}
