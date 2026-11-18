package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.hayi.shop.audit.api.AuditEntry;
import vn.hayi.shop.audit.api.AuditRecorder;
import vn.hayi.shop.audit.api.AuditSource;
import vn.hayi.shop.catalog.domain.CatalogErrorCode;
import vn.hayi.shop.catalog.domain.Category;
import vn.hayi.shop.catalog.domain.CategoryRepository;
import vn.hayi.shop.catalog.domain.FieldDiff;
import vn.hayi.shop.catalog.domain.NewVariant;
import vn.hayi.shop.catalog.domain.OptionGroup;
import vn.hayi.shop.catalog.domain.OptionGroupChoice;
import vn.hayi.shop.catalog.domain.OptionGroupRepository;
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductOptionGroupConfig;
import vn.hayi.shop.catalog.domain.ProductOptionItemOverride;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;
import vn.hayi.shop.catalog.domain.ProductType;
import vn.hayi.shop.catalog.domain.ProductVariant;
import vn.hayi.shop.shared.concurrency.ExpectedVersion;
import vn.hayi.shop.shared.error.CommonErrorCode;
import vn.hayi.shop.shared.error.DomainException;
import vn.hayi.shop.shared.error.RuleViolationsException;
import vn.hayi.shop.shared.patch.Change;

/**
 * Creates and reads brand-level Products (Catalog §5.2, API 08 §1.3, Decision Z); every change is audited in the
 * same transaction (Decision W8).
 */
@Service
public class ProductService {

	private static final Map<String, CatalogErrorCode> UNIQUE_KEYS = Map.of("uk_cat_product__product_code",
			CatalogErrorCode.CATALOG_CODE_TAKEN, "uk_cat_product__slug", CatalogErrorCode.CATALOG_SLUG_TAKEN,
			"uk_cat_product_variant__sku", CatalogErrorCode.CATALOG_SKU_TAKEN);

	private static final Sort BY_NAME = Sort.by("name", "id");

	private static final Pattern DUPLICATE_ENTRY = Pattern.compile("Duplicate entry '([^']*)'");

	private final ProductRepository products;

	private final CategoryRepository categories;

	private final OptionGroupRepository optionGroups;

	private final AuditRecorder audit;

	private final Clock clock;

	ProductService(ProductRepository products, CategoryRepository categories, OptionGroupRepository optionGroups,
			AuditRecorder audit, Clock clock) {
		this.products = products;
		this.categories = categories;
		this.optionGroups = optionGroups;
		this.audit = audit;
		this.clock = clock;
	}

	/** Codes and SKUs as stored: trimmed and upper-cased (Decision Z2). */
	public static String normalizeCode(String code) {
		return Product.normalizeCode(code);
	}

	/**
	 * Creates a {@code DRAFT} Product. Its primary Category is locked until commit, so archiving that Category waits
	 * and then sees the Product (Decision Z5, W5).
	 */
	@Transactional
	public ProductView create(NewProduct request, CatalogClient client) {
		Category category = (request.primaryCategoryId() != null) ? this.categories
			.findByIdForUpdate(request.primaryCategoryId())
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_CATEGORY_NOT_FOUND,
					"No Category " + request.primaryCategoryId()))
				: null;
		List<NewVariant> variants = request.variants().stream()
			.map(variant -> new NewVariant(variant.sku(), variant.name(), variant.basePrice(), variant.isDefault()))
			.toList();
		Product product = Product.create(request.productCode(), request.slug(), request.name(),
				ProductType.valueOf(request.productType()), category, request.shortDescription(),
				request.description(), variants, this.clock.instant());
		if (this.products.existsByProductCode(product.getProductCode())) {
			throw taken(CatalogErrorCode.CATALOG_CODE_TAKEN, product.getProductCode());
		}
		if (this.products.existsBySlug(product.getSlug())) {
			throw taken(CatalogErrorCode.CATALOG_SLUG_TAKEN, product.getSlug());
		}
		Map<String, String> skuFields = new LinkedHashMap<>();
		List<ProductVariant> created = product.getVariants();
		for (int i = 0; i < created.size(); i++) {
			skuFields.put(created.get(i).getSku(), "variants[" + i + "].sku");
		}
		requireFreeSkus(skuFields);
		save(product, skuFields);
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, "PRODUCT_CREATE", "PRODUCT",
				product.getId())
			.withNewValue(snapshot(product))
			.withClient(client.ipAddress(), client.userAgent()));
		return view(product);
	}

	/**
	 * Merge Patch of the descriptive fields at {@code expected} version (Decision AE1). A new primary Category is
	 * locked until commit, as on create (Z5); a patch that changes nothing keeps the version and is not audited.
	 */
	@Transactional
	public ProductView update(UUID productId, Change<String> name, Change<String> slug,
			Change<UUID> primaryCategoryId, Change<String> shortDescription, Change<String> description,
			ExpectedVersion expected, CatalogClient client) {
		Product product = find(productId, expected);
		product.requireChangeable();
		UUID categoryId = primaryCategoryId.applyTo(product.getPrimaryCategoryId());
		Category category = null;
		if (categoryId != null && !categoryId.equals(product.getPrimaryCategoryId())) {
			category = this.categories.findByIdForUpdate(categoryId)
				.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_CATEGORY_NOT_FOUND,
						"No Category " + categoryId));
		}
		String newSlug = slug.applyTo(product.getSlug());
		if (!newSlug.equals(product.getSlug()) && this.products.existsBySlug(newSlug)) {
			throw taken(CatalogErrorCode.CATALOG_SLUG_TAKEN, newSlug);
		}
		FieldDiff diff = product.edit(name.applyTo(product.getName()), newSlug, categoryId, category,
				shortDescription.applyTo(product.getShortDescription()),
				description.applyTo(product.getDescription()), this.clock.instant());
		if (!diff.isEmpty()) {
			save(product);
			record(client, "PRODUCT_UPDATE", product, diff.oldValues(), diff.newValues());
		}
		return view(product);
	}

	/** Adds a Variant (Decision AE2); the caller has checked both permissions. */
	@Transactional
	public ProductView addVariant(UUID productId, NewProduct.Variant request, ExpectedVersion expected,
			CatalogClient client) {
		Product product = find(productId, expected);
		product.requireChangeable();
		String sku = normalizeCode(request.sku());
		Map<String, String> skuFields = Map.of(sku, "sku");
		requireFreeSkus(skuFields);
		UUID previousDefault = product.defaultVariant().map(ProductVariant::getId).orElse(null);
		ProductVariant added = product.addVariant(
				new NewVariant(sku, request.name(), request.basePrice(), request.isDefault()), this.clock.instant(),
				() -> this.products.saveAndFlush(product));
		save(product, skuFields);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("variantId", added.getId());
		values.put("sku", added.getSku());
		values.put("name", added.getName());
		values.put("basePrice", added.getBasePrice());
		values.put("isDefault", added.isDefault());
		Map<String, Object> oldValue = null;
		if (added.isDefault()) {
			// The default moved (AE5): record the old one, null when no Variant was left.
			oldValue = new LinkedHashMap<>();
			oldValue.put("defaultVariantId", previousDefault);
			values.put("defaultVariantId", added.getId());
		}
		record(client, "PRODUCT_VARIANT_CREATE", product, oldValue, values);
		return view(product);
	}

	/**
	 * Merge Patch of one Variant at the Product's {@code expected} version (Decision AE3). A real change of its base
	 * price needs {@code CATALOG_PRICE_MANAGE}, which the caller reports as {@code mayChangePrice}; it is checked
	 * before the business rules (as AB4).
	 */
	@Transactional
	public ProductView updateVariant(UUID productId, UUID variantId, Change<String> name, Change<Long> basePrice,
			boolean makeDefault, boolean mayChangePrice, ExpectedVersion expected, CatalogClient client) {
		Product product = find(productId, expected);
		product.requireChangeable();
		ProductVariant variant = product.findVariant(variantId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_VARIANT_NOT_FOUND,
					"No Variant " + variantId + " in Product " + productId));
		long newPrice = basePrice.applyTo(variant.getBasePrice());
		if (newPrice != variant.getBasePrice() && !mayChangePrice) {
			throw new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission CATALOG_PRICE_MANAGE");
		}
		FieldDiff diff = product.editVariant(variantId, name.applyTo(variant.getName()), newPrice, makeDefault,
				this.clock.instant(), () -> this.products.saveAndFlush(product));
		if (!diff.isEmpty()) {
			save(product);
			record(client, "PRODUCT_VARIANT_UPDATE", product, withVariant(variantId, diff.oldValues()),
					withVariant(variantId, diff.newValues()));
		}
		return view(product);
	}

	/** Archives a Variant for good (Decision AE4). */
	@Transactional
	public ProductView archiveVariant(UUID productId, UUID variantId, ExpectedVersion expected,
			CatalogClient client) {
		Product product = find(productId, expected);
		product.requireChangeable();
		String before = product.findVariant(variantId).map(variant -> variant.getStatus().name()).orElse(null);
		ProductVariant archived = product.archiveVariant(variantId, this.clock.instant());
		save(product);
		record(client, "PRODUCT_VARIANT_ARCHIVE", product, withVariant(variantId, Map.of("status", before)),
				withVariant(variantId, Map.of("status", archived.getStatus().name())));
		return view(product);
	}

	private static Map<String, Object> withVariant(UUID variantId, Map<String, Object> values) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("variantId", variantId);
		entry.putAll(values);
		return entry;
	}

	/**
	 * {@code DRAFT | INACTIVE → ACTIVE} (Decision AA). The primary Category is locked so it cannot be archived while
	 * this Product goes on sale. {@code expected} is {@code null} without {@code If-Match}.
	 */
	@Transactional
	public ProductView activate(UUID productId, ExpectedVersion expected, CatalogClient client) {
		Product product = find(productId, expected);
		Category category = (product.getPrimaryCategoryId() != null)
				? this.categories.findByIdForUpdate(product.getPrimaryCategoryId()).orElse(null) : null;
		return transitioned(product, product.activate(category, attachedGroups(product), this.clock.instant()),
				"PRODUCT_ACTIVATE", client);
	}

	/**
	 * Replaces the option groups the Product offers (Decision AC2) at {@code expected} version. Every named group must
	 * exist; a change that changes nothing keeps the version and is not audited.
	 */
	@Transactional
	public ProductView replaceOptionGroups(UUID productId, List<GroupChoice> requested, ExpectedVersion expected,
			CatalogClient client) {
		List<OptionGroupChoice> choices = requested.stream()
			.map(choice -> new OptionGroupChoice(choice.optionGroupId(), choice.minSelectOverride(),
					choice.maxSelectOverride()))
			.toList();
		Product product = find(productId, expected);
		Map<UUID, OptionGroup> named = groupsById(choices.stream().map(OptionGroupChoice::optionGroupId).toList());
		for (OptionGroupChoice choice : choices) {
			if (!named.containsKey(choice.optionGroupId())) {
				throw new DomainException(CatalogErrorCode.CATALOG_OPTION_GROUP_NOT_FOUND,
						"No option group " + choice.optionGroupId());
			}
		}
		List<Map<String, Object>> before = groupsSnapshot(product);
		if (product.replaceOptionGroups(choices, named, this.clock.instant())) {
			this.products.saveAndFlush(product);
			record(client, "PRODUCT_OPTION_GROUPS_REPLACE", product, Map.of("groups", before),
					Map.of("groups", groupsSnapshot(product)));
		}
		return view(product);
	}

	/**
	 * Replaces the item overrides (Decision AC3). Changing an override price needs {@code CATALOG_PRICE_MANAGE}, which
	 * the caller reports as {@code mayChangePrice}; it is checked before the business rules (as AB4).
	 */
	@Transactional
	public ProductView replaceItemOverrides(UUID productId, List<ItemOverride> requested, boolean mayChangePrice,
			ExpectedVersion expected, CatalogClient client) {
		List<ProductOptionItemOverride> overrides = requested.stream()
			.map(override -> new ProductOptionItemOverride(override.optionItemId(), override.extraPriceOverride(),
					override.isHidden()))
			.toList();
		Product product = find(productId, expected);
		if (!mayChangePrice && product.changesOverridePrices(overrides)) {
			throw new DomainException(CommonErrorCode.PERMISSION_DENIED, "Missing permission CATALOG_PRICE_MANAGE");
		}
		List<Map<String, Object>> before = overridesSnapshot(product);
		if (product.replaceItemOverrides(overrides, attachedGroups(product), this.clock.instant())) {
			this.products.saveAndFlush(product);
			record(client, "PRODUCT_OPTION_ITEM_OVERRIDES_REPLACE", product, Map.of("overrides", before),
					Map.of("overrides", overridesSnapshot(product)));
		}
		return view(product);
	}

	/** One entry of {@code PUT …/option-groups}; {@code null} override = the group's own value (Decision AC2). */
	public record GroupChoice(UUID optionGroupId, Integer minSelectOverride, Integer maxSelectOverride) {
	}

	/** One entry of {@code PUT …/option-item-overrides}; {@code null} price = library price (Decision AC3). */
	public record ItemOverride(UUID optionItemId, Long extraPriceOverride, boolean isHidden) {
	}

	private Map<UUID, OptionGroup> attachedGroups(Product product) {
		return groupsById(product.getOptionGroups().stream().map(ProductOptionGroupConfig::optionGroupId).toList());
	}

	private Map<UUID, OptionGroup> groupsById(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, OptionGroup> byId = new LinkedHashMap<>();
		this.optionGroups.findByIdIn(ids).forEach(group -> byId.put(group.getId(), group));
		return byId;
	}

	private ProductView view(Product product) {
		return ProductView.of(product, attachedGroups(product));
	}

	private static List<Map<String, Object>> groupsSnapshot(Product product) {
		return product.getOptionGroups().stream().map(config -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("optionGroupId", config.optionGroupId());
			entry.put("minSelectOverride", config.minSelectOverride());
			entry.put("maxSelectOverride", config.maxSelectOverride());
			return entry;
		}).toList();
	}

	private static List<Map<String, Object>> overridesSnapshot(Product product) {
		return product.getOptionItemOverrides()
			.stream()
			.sorted(Comparator.comparing(ProductOptionItemOverride::optionItemId))
			.map(override -> {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("optionItemId", override.optionItemId());
				entry.put("extraPriceOverride", override.extraPriceOverride());
				entry.put("isHidden", override.isHidden());
				return entry;
			})
			.toList();
	}

	private void record(CatalogClient client, String action, Product product, Map<String, ?> oldValue,
			Map<String, ?> newValue) {
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "PRODUCT", product.getId())
			.withOldValue(oldValue)
			.withNewValue(newValue)
			.withClient(client.ipAddress(), client.userAgent()));
	}

	/** {@code ACTIVE → INACTIVE} (Decision AA1). */
	@Transactional
	public ProductView deactivate(UUID productId, ExpectedVersion expected, CatalogClient client) {
		Product product = find(productId, expected);
		return transitioned(product, product.deactivate(this.clock.instant()), "PRODUCT_DEACTIVATE", client);
	}

	/** {@code DRAFT | ACTIVE | INACTIVE → ARCHIVED}, final (Decision AA1). */
	@Transactional
	public ProductView archive(UUID productId, ExpectedVersion expected, CatalogClient client) {
		Product product = find(productId, expected);
		return transitioned(product, product.archive(this.clock.instant()), "PRODUCT_ARCHIVE", client);
	}

	private Product find(UUID productId, ExpectedVersion expected) {
		Product product = this.products.findById(productId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_PRODUCT_NOT_FOUND,
					"No Product " + productId));
		if (expected != null) {
			expected.verify(product.getVersion());
		}
		return product;
	}

	private ProductView transitioned(Product product, ProductStatus previous, String action,
			CatalogClient client) {
		this.products.saveAndFlush(product);
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, action, "PRODUCT", product.getId())
			.withOldValue(Map.of("status", previous.name()))
			.withNewValue(Map.of("status", product.getStatus().name()))
			.withClient(client.ipAddress(), client.userAgent()));
		return view(product);
	}

	@Transactional(readOnly = true)
	public ProductView get(UUID productId) {
		return view(find(productId, null));
	}

	/**
	 * One page sorted by name (Decision Z6). {@code statuses} empty means every status; {@code query} matches name,
	 * code or SKU, case-insensitive, as plain text.
	 */
	@Transactional(readOnly = true)
	public Page<ProductSummary> list(Collection<String> statuses, UUID categoryId, String query, int page,
			int size) {
		Collection<ProductStatus> wanted = statuses.isEmpty() ? EnumSet.allOf(ProductStatus.class)
				: statuses.stream().map(ProductStatus::valueOf).toList();
		return this.products.search(wanted, categoryId, pattern(query), PageRequest.of(page, size, BY_NAME))
			.map(ProductSummary::of);
	}

	/** {@code %…%} with {@code LIKE} wildcards escaped by {@code !}, or {@code null} for no text filter. */
	private static String pattern(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		String literal = query.trim()
			.toLowerCase(Locale.ROOT)
			.replace("!", "!!")
			.replace("%", "!%")
			.replace("_", "!_");
		return "%" + literal + "%";
	}

	private void save(Product product) {
		save(product, Map.of());
	}

	/**
	 * @param skuFields the request field of each new SKU (as stored), so a SKU a concurrent request took after the
	 * checks is still reported on its field (Decision Z2, AE2). MySQL stops at the first duplicate, so this path
	 * reports only that one SKU; the pre-check reports all of them.
	 */
	private void save(Product product, Map<String, String> skuFields) {
		try {
			this.products.saveAndFlush(product);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create took a code, slug or SKU after the checks; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null) {
				for (Map.Entry<String, CatalogErrorCode> key : UNIQUE_KEYS.entrySet()) {
					if (message.contains(key.getKey())) {
						String value = duplicateValue(message);
						if (key.getValue() == CatalogErrorCode.CATALOG_SKU_TAKEN && skuFields.containsKey(value)) {
							throw skusTaken(List.of(value), skuFields);
						}
						throw taken(key.getValue(), value);
					}
				}
			}
			throw ex;
		}
	}

	/** {@code skuFields}: request field by SKU as stored, in request order. */
	private void requireFreeSkus(Map<String, String> skuFields) {
		List<String> takenSkus = this.products.findTakenSkus(skuFields.keySet());
		if (!takenSkus.isEmpty()) {
			throw skusTaken(takenSkus, skuFields);
		}
	}

	/** {@code 409 CATALOG_SKU_TAKEN} with one {@code errors[]} entry per taken SKU, in request order (Z2). */
	private static RuleViolationsException skusTaken(Collection<String> takenSkus, Map<String, String> skuFields) {
		List<String> inOrder = skuFields.keySet().stream().filter(takenSkus::contains).toList();
		List<RuleViolationsException.Violation> violations = inOrder.stream()
			.map(sku -> new RuleViolationsException.Violation(skuFields.get(sku),
					CatalogErrorCode.CATALOG_SKU_TAKEN.name()))
			.toList();
		return new RuleViolationsException(CatalogErrorCode.CATALOG_SKU_TAKEN, violations, String.join(", ", inOrder));
	}

	/** MySQL names the value: {@code Duplicate entry 'ML-M' for key 'cat_product_variant.uk_…'}. */
	private static String duplicateValue(String message) {
		Matcher matcher = DUPLICATE_ENTRY.matcher(message);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static Map<String, Object> snapshot(Product product) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("productCode", product.getProductCode());
		values.put("slug", product.getSlug());
		values.put("name", product.getName());
		values.put("productType", product.getProductType().name());
		values.put("primaryCategoryId", product.getPrimaryCategoryId());
		values.put("variants", product.getVariants().stream()
			.map(variant -> Map.of("sku", variant.getSku(), "basePrice", variant.getBasePrice(), "isDefault",
					variant.isDefault()))
			.toList());
		return values;
	}

	private static DomainException taken(CatalogErrorCode code, String value) {
		return new DomainException(code, code + ": " + value, value);
	}

}
