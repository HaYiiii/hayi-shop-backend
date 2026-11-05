package vn.hayi.shop.catalog.application;

import java.time.Clock;
import java.util.Collection;
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
import vn.hayi.shop.catalog.domain.Product;
import vn.hayi.shop.catalog.domain.ProductRepository;
import vn.hayi.shop.catalog.domain.ProductStatus;
import vn.hayi.shop.catalog.domain.ProductType;
import vn.hayi.shop.catalog.domain.ProductVariant;
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

	private final AuditRecorder audit;

	private final Clock clock;

	ProductService(ProductRepository products, CategoryRepository categories, AuditRecorder audit, Clock clock) {
		this.products = products;
		this.categories = categories;
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
	public ProductView create(NewProduct request, CategoryService.Client client) {
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
		return ProductView.of(product);
	}

	@Transactional(readOnly = true)
	public ProductView get(UUID productId) {
		return ProductView.of(this.products.findById(productId)
			.orElseThrow(() -> new DomainException(CatalogErrorCode.CATALOG_PRODUCT_NOT_FOUND,
					"No Product " + productId)));
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
