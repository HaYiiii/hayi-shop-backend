package vn.hayi.shop.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

	/** Non-archived Variants a Product may have (Decision Z1, AE2). */
	public static final int MAX_LIVE_VARIANTS = 20;

	/** Images a Product may have (Decision AG1). */
	public static final int MAX_IMAGES = 10;

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

	/** Images in order, one primary (Decision AG); as for Variants, a change touches {@code updatedAt}. */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	@OrderBy("sortOrder")
	@BatchSize(size = 100)
	private List<ProductImage> images = new ArrayList<>();

	/** When it may be sold (Decision AK); none = always. As for Variants, a change touches {@code updatedAt}. */
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@OptimisticLock(excluded = true)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	@OrderBy("startDate ASC, endDate ASC, id ASC")
	@BatchSize(size = 100)
	private List<ProductSellingSchedule> sellingSchedules = new ArrayList<>();

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

	/** Its Tags (Decision AL2), kept as ids; as for Variants, a change touches {@code updatedAt}. */
	@ElementCollection
	@CollectionTable(name = "cat_product_tag", joinColumns = @JoinColumn(name = "product_id"))
	@Column(name = "tag_id")
	@OptimisticLock(excluded = true)
	@BatchSize(size = 100)
	private Set<UUID> tagIds = new HashSet<>();

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

	/**
	 * Replaces its Tags, which the caller checked exist (Decision AL2). Returns whether anything changed.
	 * @throws DomainException {@code CATALOG_PRODUCT_INVALID_TRANSITION} when archived
	 */
	public boolean replaceTags(Set<UUID> tagIds, Instant now) {
		requireNotArchived("tag");
		if (this.tagIds.equals(tagIds)) {
			return false;
		}
		this.tagIds.clear();
		this.tagIds.addAll(tagIds);
		this.updatedAt = now;
		return true;
	}

	public Set<UUID> getTagIds() {
		return Set.copyOf(this.tagIds);
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
		requireNotArchived("configure the options of");
	}

	private void requireNotArchived(String action) {
		requireStatus(action, ProductStatus.DRAFT, ProductStatus.ACTIVE, ProductStatus.INACTIVE);
	}

	/**
	 * Edits the descriptive fields (Decision AE1) and returns what changed; an edit that changes nothing leaves the
	 * Product untouched.
	 * @param newPrimaryCategory the Category {@code primaryCategoryId} names, locked by the caller, when that differs
	 * from the current one; otherwise ignored
	 * @throws DomainException {@code CATALOG_PRODUCT_INVALID_TRANSITION} when archived, {@code CATALOG_CATEGORY_ARCHIVED}
	 * @throws ProductActivationInvalidException when an {@code ACTIVE} Product would lose its primary Category (AE4)
	 */
	public FieldDiff edit(String name, String slug, UUID primaryCategoryId, Category newPrimaryCategory,
			String shortDescription, String description, Instant now) {
		requireNotArchived("edit");
		if (primaryCategoryId != null && !primaryCategoryId.equals(this.primaryCategoryId)) {
			newPrimaryCategory.requireActive();
		}
		if (primaryCategoryId == null && this.status == ProductStatus.ACTIVE) {
			throw new ProductActivationInvalidException(this.id,
					List.of(ProductActivationInvalidException.Issue.of(ActivationIssue.PRIMARY_CATEGORY_MISSING)));
		}
		FieldDiff diff = new FieldDiff();
		this.name = diff.track("name", this.name, name);
		this.slug = diff.track("slug", this.slug, slug);
		this.primaryCategoryId = diff.track("primaryCategoryId", this.primaryCategoryId, primaryCategoryId);
		this.shortDescription = diff.track("shortDescription", this.shortDescription, shortDescription);
		this.description = diff.track("description", this.description, description);
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
		return diff;
	}

	/**
	 * Adds an {@code ACTIVE} Variant last (Decision AE2). It becomes the default when asked, or when no other
	 * non-archived Variant is left.
	 * @param writeReleasedDefault writes the Product once the previous default has let go of the flag. The database
	 * allows one non-archived default per Product ({@code default_marker}), checked per statement, and inserts run
	 * before updates in one flush; so the old default must be written first.
	 * @throws DomainException {@code CATALOG_PRODUCT_INVALID_TRANSITION} when archived,
	 * {@code CATALOG_VARIANT_LIMIT_REACHED}; {@code CATALOG_PRICE_NEGATIVE} with {@code errors[]}
	 */
	public ProductVariant addVariant(NewVariant variant, Instant now, Runnable writeReleasedDefault) {
		requireNotArchived("add a Variant to");
		requireNotNegative(variant.basePrice());
		List<ProductVariant> live = liveVariants();
		if (live.size() >= MAX_LIVE_VARIANTS) {
			throw new DomainException(CatalogErrorCode.CATALOG_VARIANT_LIMIT_REACHED,
					"Product " + this.id + " already has " + MAX_LIVE_VARIANTS + " Variants");
		}
		boolean isDefault = variant.isDefault() || live.isEmpty();
		this.updatedAt = now;
		if (isDefault) {
			releaseDefault(now, writeReleasedDefault);
		}
		int sortOrder = this.variants.stream().mapToInt(ProductVariant::getSortOrder).max().orElse(-1) + 1;
		ProductVariant added = ProductVariant.create(
				new NewVariant(variant.sku(), variant.name(), variant.basePrice(), isDefault), sortOrder, now);
		this.variants.add(added);
		return added;
	}

	/**
	 * Edits a Variant's name and base price, and makes it the default when {@code makeDefault} (Decision AE3).
	 * Returns what changed, with {@code defaultVariantId} when the default moved.
	 * @param writeReleasedDefault as for {@link #addVariant}
	 * @throws DomainException {@code CATALOG_VARIANT_NOT_FOUND}, {@code CATALOG_VARIANT_ARCHIVED},
	 * {@code CATALOG_PRODUCT_INVALID_TRANSITION}; {@code CATALOG_PRICE_NEGATIVE} with {@code errors[]}
	 */
	public FieldDiff editVariant(UUID variantId, String name, long basePrice, boolean makeDefault, Instant now,
			Runnable writeReleasedDefault) {
		requireNotArchived("edit a Variant of");
		ProductVariant variant = liveVariant(variantId);
		requireNotNegative(basePrice);
		FieldDiff diff = new FieldDiff();
		variant.edit(name, basePrice, diff, now);
		if (makeDefault && !variant.isDefault()) {
			UUID previous = defaultVariant().map(ProductVariant::getId).orElse(null);
			diff.track("isDefault", false, true);
			diff.track("defaultVariantId", previous, variantId);
			this.updatedAt = now;
			releaseDefault(now, writeReleasedDefault);
			variant.setDefault(true, now);
		}
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
		return diff;
	}

	/**
	 * Archives a Variant for good (Decision AE4). The default may go only as the last non-archived Variant, and an
	 * {@code ACTIVE} Product keeps an {@code ACTIVE} Variant.
	 * @throws DomainException {@code CATALOG_VARIANT_IS_DEFAULT}, {@code CATALOG_VARIANT_NOT_FOUND},
	 * {@code CATALOG_VARIANT_ARCHIVED}, {@code CATALOG_PRODUCT_INVALID_TRANSITION}
	 * @throws ProductActivationInvalidException for the last {@code ACTIVE} Variant of an {@code ACTIVE} Product
	 */
	public ProductVariant archiveVariant(UUID variantId, Instant now) {
		requireNotArchived("archive a Variant of");
		ProductVariant variant = liveVariant(variantId);
		if (variant.isDefault() && liveVariants().size() > 1) {
			throw new DomainException(CatalogErrorCode.CATALOG_VARIANT_IS_DEFAULT,
					"Variant " + variantId + " is the default of Product " + this.id);
		}
		boolean lastActive = this.variants.stream()
			.noneMatch(other -> other != variant && other.getStatus() == VariantStatus.ACTIVE);
		if (lastActive && this.status == ProductStatus.ACTIVE) {
			throw new ProductActivationInvalidException(this.id,
					List.of(ProductActivationInvalidException.Issue.of(ActivationIssue.NO_ACTIVE_VARIANT)));
		}
		variant.archive(now);
		this.updatedAt = now;
		return variant;
	}

	private void releaseDefault(Instant now, Runnable writeReleasedDefault) {
		Optional<ProductVariant> current = defaultVariant();
		if (current.isPresent()) {
			current.get().setDefault(false, now);
			writeReleasedDefault.run();
		}
	}

	/**
	 * Adds an image last (Decision AG1). It becomes the primary when asked or when it is the first one.
	 * @param writeReleasedPrimary writes the Product once the previous primary has let go of the flag
	 * ({@code primary_marker} is unique per statement; as for the default Variant)
	 * @throws DomainException {@code CATALOG_IMAGE_LIMIT_REACHED}, {@code CATALOG_PRODUCT_INVALID_TRANSITION}
	 */
	public ProductImage addImage(UUID storedObjectId, String altText, boolean makePrimary, Instant now,
			Runnable writeReleasedPrimary) {
		requireNotArchived("add an image to");
		if (this.images.size() >= MAX_IMAGES) {
			throw new DomainException(CatalogErrorCode.CATALOG_IMAGE_LIMIT_REACHED,
					"Product " + this.id + " already has " + MAX_IMAGES + " images");
		}
		boolean isPrimary = makePrimary || this.images.isEmpty();
		this.updatedAt = now;
		if (isPrimary) {
			releasePrimary(writeReleasedPrimary);
		}
		int sortOrder = this.images.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;
		ProductImage image = ProductImage.create(storedObjectId, altText, sortOrder, isPrimary, now);
		this.images.add(image);
		return image;
	}

	/**
	 * Edits an image's alt text and makes it the primary when {@code makePrimary} (Decision AG2). Returns what
	 * changed, with {@code primaryImageId} when the primary moved.
	 */
	public FieldDiff editImage(UUID imageId, String altText, boolean makePrimary, Instant now,
			Runnable writeReleasedPrimary) {
		requireNotArchived("edit an image of");
		ProductImage image = image(imageId);
		FieldDiff diff = new FieldDiff();
		image.setAltText(diff.track("altText", image.getAltText(), altText));
		if (makePrimary && !image.isPrimary()) {
			UUID previous = primaryImage().map(ProductImage::getId).orElse(null);
			diff.track("isPrimary", false, true);
			diff.track("primaryImageId", previous, imageId);
			this.updatedAt = now;
			releasePrimary(writeReleasedPrimary);
			image.setPrimary(true);
		}
		if (!diff.isEmpty()) {
			this.updatedAt = now;
		}
		return diff;
	}

	/**
	 * Removes an image (Decision AG2); when it was the primary, the first one left takes over. Returns the removed
	 * image, whose stored object the caller archives.
	 * @param writeReleasedPrimary as for {@link #addImage}
	 */
	public ProductImage removeImage(UUID imageId, Instant now, Runnable writeReleasedPrimary) {
		requireNotArchived("remove an image of");
		ProductImage image = image(imageId);
		this.updatedAt = now;
		boolean wasPrimary = image.isPrimary();
		if (wasPrimary && this.images.size() > 1) {
			image.setPrimary(false);
			writeReleasedPrimary.run();
		}
		this.images.remove(image);
		if (wasPrimary && !this.images.isEmpty()) {
			this.images.stream()
				.min(Comparator.comparingInt(ProductImage::getSortOrder))
				.ifPresent(next -> next.setPrimary(true));
		}
		return image;
	}

	/**
	 * Puts the images in this order (Decision AG3); returns whether anything changed.
	 * @throws DomainException {@code CATALOG_IMAGE_ORDER_STALE} unless {@code imageIds} is exactly the current set
	 */
	public boolean reorderImages(List<UUID> imageIds, Instant now) {
		requireNotArchived("reorder the images of");
		Set<UUID> current = this.images.stream().map(ProductImage::getId).collect(Collectors.toSet());
		if (imageIds.size() != current.size() || !current.containsAll(imageIds)) {
			throw new DomainException(CatalogErrorCode.CATALOG_IMAGE_ORDER_STALE,
					"The images of Product " + this.id + " have changed");
		}
		List<UUID> inOrder = this.images.stream()
			.sorted(Comparator.comparingInt(ProductImage::getSortOrder))
			.map(ProductImage::getId)
			.toList();
		if (inOrder.equals(imageIds)) {
			return false;
		}
		for (int i = 0; i < imageIds.size(); i++) {
			image(imageIds.get(i)).setSortOrder(i);
		}
		this.updatedAt = now;
		return true;
	}

	private void releasePrimary(Runnable writeReleasedPrimary) {
		Optional<ProductImage> current = primaryImage();
		if (current.isPresent()) {
			current.get().setPrimary(false);
			writeReleasedPrimary.run();
		}
	}

	private Optional<ProductImage> primaryImage() {
		return this.images.stream().filter(ProductImage::isPrimary).findFirst();
	}

	private ProductImage image(UUID imageId) {
		return this.images.stream()
			.filter(image -> image.getId().equals(imageId))
			.findFirst()
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_IMAGE_NOT_FOUND,
					"No image " + imageId + " in Product " + this.id));
	}

	/** The images in their order. */
	public List<ProductImage> getImages() {
		return this.images.stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder)).toList();
	}

	/**
	 * Replaces the selling schedules (Decision AK1); returns whether anything changed. Order of schedules and windows
	 * does not matter.
	 * @throws vn.hayi.shop.shared.error.RuleViolationsException {@code CATALOG_SELLING_SCHEDULE_INVALID}
	 */
	public boolean replaceSellingSchedules(List<SellingScheduleSpec> schedules, Instant now) {
		requireNotArchived("set the selling schedules of");
		SellingScheduleSpec.requireValid(schedules);
		List<SellingScheduleSpec> normalized = schedules.stream()
			.map(spec -> new SellingScheduleSpec(spec.startDate(), spec.endDate(),
					spec.windows().stream().sorted(ProductSellingSchedule.WINDOW_ORDER).toList()))
			.toList();
		if (sameSchedules(getSellingSchedules(), normalized)) {
			return false;
		}
		this.sellingSchedules.clear();
		normalized.forEach(spec -> this.sellingSchedules.add(ProductSellingSchedule.of(spec, now)));
		this.updatedAt = now;
		return true;
	}

	private static boolean sameSchedules(List<SellingScheduleSpec> current, List<SellingScheduleSpec> next) {
		// Order does not matter, but repeats do: [A, A, B] is not [A, B, B].
		return current.size() == next.size() && counts(current).equals(counts(next));
	}

	private static Map<SellingScheduleSpec, Long> counts(List<SellingScheduleSpec> schedules) {
		return schedules.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
	}

	/** The schedules as values, windows in a stable order. */
	public List<SellingScheduleSpec> getSellingSchedules() {
		return this.sellingSchedules.stream().map(ProductSellingSchedule::spec).toList();
	}

	/** Whether it may be sold at that local date and time: no schedule, or one that sells then (AK2). */
	public boolean isOnSaleAt(LocalDate date, LocalTime time) {
		return this.sellingSchedules.isEmpty() || getSellingSchedules().stream()
			.anyMatch(schedule -> schedule.sellsAt(date, time));
	}

	/** The Variant with this id, archived or not. */
	public Optional<ProductVariant> findVariant(UUID variantId) {
		return this.variants.stream().filter(variant -> variant.getId().equals(variantId)).findFirst();
	}

	/**
	 * Refuses any change to an archived Product (Decision AE1, AC1); callers check it before looking anything else
	 * up, so an archived Product answers {@code 409} whatever else the request names.
	 */
	public void requireChangeable() {
		requireNotArchived("change");
	}

	/** The non-archived default Variant, if any Variant is left. */
	public Optional<ProductVariant> defaultVariant() {
		return liveVariants().stream().filter(ProductVariant::isDefault).findFirst();
	}

	private ProductVariant liveVariant(UUID variantId) {
		ProductVariant variant = findVariant(variantId).orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_VARIANT_NOT_FOUND,
					"No Variant " + variantId + " in Product " + this.id));
		if (variant.getStatus() == VariantStatus.ARCHIVED) {
			throw new DomainException(CatalogErrorCode.CATALOG_VARIANT_ARCHIVED, "Variant " + variantId + " is archived");
		}
		return variant;
	}

	private static void requireNotNegative(long basePrice) {
		if (basePrice < 0) {
			throw new RuleViolationsException(CatalogErrorCode.CATALOG_PRICE_NEGATIVE,
					List.of(new RuleViolationsException.Violation("basePrice", "MIN")));
		}
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
