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
		List<String> takenSkus = this.products
			.findTakenSkus(product.getVariants().stream().map(ProductVariant::getSku).toList());
		if (!takenSkus.isEmpty()) {
			throw taken(CatalogErrorCode.CATALOG_SKU_TAKEN, String.join(", ", takenSkus));
		}
		save(product);
		this.audit.record(AuditEntry.of(client.actor(), AuditSource.BACKOFFICE_WEB, "PRODUCT_CREATE", "PRODUCT",
				product.getId())
			.withNewValue(snapshot(product))
			.withClient(client.ipAddress(), client.userAgent()));
		return view(product);
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
		try {
			this.products.saveAndFlush(product);
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent create took a code, slug or SKU after the checks; any other violation is a bug.
			String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
			if (message != null) {
				for (Map.Entry<String, CatalogErrorCode> key : UNIQUE_KEYS.entrySet()) {
					if (message.contains(key.getKey())) {
						throw taken(key.getValue(), duplicateValue(message));
					}
				}
			}
			throw ex;
		}
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
