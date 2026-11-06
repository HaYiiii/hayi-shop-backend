package vn.hayi.shop.catalog.web.backoffice;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vn.hayi.shop.catalog.application.CategoryService;
import vn.hayi.shop.catalog.application.NewProduct;
import vn.hayi.shop.catalog.application.ProductService;
import vn.hayi.shop.catalog.application.ProductSummary;
import vn.hayi.shop.catalog.application.ProductView;
import vn.hayi.shop.platform.security.AuthenticatedSession;
import vn.hayi.shop.platform.security.ClientInfo;
import vn.hayi.shop.platform.security.Permission;
import vn.hayi.shop.platform.security.RequiresPermission;
import vn.hayi.shop.platform.web.InvalidFieldsException;
import vn.hayi.shop.platform.web.VersionTags;
import vn.hayi.shop.platform.web.spec.backoffice.api.ProductsApi;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductCreateRequestDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductPageDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductPagePageDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductStatusDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductSummaryDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductTypeDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductVariantDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.ProductVariantInputDto;
import vn.hayi.shop.platform.web.spec.backoffice.model.VariantStatusDto;
import vn.hayi.shop.shared.actor.ActorContext;

/** Backoffice Product endpoints (API 08 §1.3, Decision Z). Brand-level: a permission at any scope is enough (M1). */
@RestController
class BackofficeProductController implements ProductsApi {

	private static final String PRODUCTS = "/api/backoffice/v1/products/";

	private final ProductService products;

	private final HttpServletRequest request;

	BackofficeProductController(ProductService products, HttpServletRequest request) {
		this.products = products;
		this.request = request;
	}

	/** Creating sets prices, so it needs both permissions (Decision Z4). */
	@Override
	@RequiresPermission({ Permission.CATALOG_MANAGE, Permission.CATALOG_PRICE_MANAGE })
	public ResponseEntity<ProductDto> createProduct(ProductCreateRequestDto body, String xCsrfToken) {
		String type = (body.getProductType() != null) ? body.getProductType().getValue() : "STANDARD";
		ProductView product = this.products.create(new NewProduct(body.getProductCode(), body.getSlug(),
				body.getName(), type, body.getPrimaryCategoryId(), body.getShortDescription(), body.getDescription(),
				toVariants(body.getVariants())), client());
		return ResponseEntity.created(URI.create(PRODUCTS + product.productId()))
			.eTag(VersionTags.of(product.version()))
			.body(toDto(product));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<ProductDto> activateProduct(UUID productId, String ifMatch, String xCsrfToken) {
		return withETag(this.products.activate(productId, VersionTags.optional(ifMatch), client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<ProductDto> deactivateProduct(UUID productId, String ifMatch, String xCsrfToken) {
		return withETag(this.products.deactivate(productId, VersionTags.optional(ifMatch), client()));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_MANAGE)
	public ResponseEntity<ProductDto> archiveProduct(UUID productId, String ifMatch, String xCsrfToken) {
		return withETag(this.products.archive(productId, VersionTags.optional(ifMatch), client()));
	}

	private static ResponseEntity<ProductDto> withETag(ProductView product) {
		return ResponseEntity.ok().eTag(VersionTags.of(product.version())).body(toDto(product));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<ProductDto> getProduct(UUID productId) {
		ProductView product = this.products.get(productId);
		return ResponseEntity.ok().eTag(VersionTags.of(product.version())).body(toDto(product));
	}

	@Override
	@RequiresPermission(Permission.CATALOG_VIEW)
	public ResponseEntity<ProductPageDto> listProducts(Integer page, Integer size, List<ProductStatusDto> status,
			UUID categoryId, String q) {
		Set<String> statuses = new HashSet<>();
		if (status != null) {
			status.forEach(value -> statuses.add(value.getValue()));
		}
		Page<ProductSummary> result = this.products.list(statuses, categoryId, q, page, size);
		return ResponseEntity.ok(new ProductPageDto(
				result.getContent().stream().map(BackofficeProductController::toDto).toList(),
				new ProductPagePageDto(result.getNumber(), result.getSize(), result.getTotalElements(),
						result.getTotalPages())));
	}

	/**
	 * One Variant is the default; with more, exactly one says so (Decision Z3). A SKU may appear once, compared as
	 * stored (upper-case, Z2).
	 */
	private static List<NewProduct.Variant> toVariants(List<ProductVariantInputDto> inputs) {
		List<InvalidFieldsException.Violation> violations = new ArrayList<>();
		Set<String> skus = new HashSet<>();
		for (int i = 0; i < inputs.size(); i++) {
			if (!skus.add(ProductService.normalizeCode(inputs.get(i).getSku()))) {
				violations.add(new InvalidFieldsException.Violation("variants[" + i + "].sku", "DUPLICATE"));
			}
		}
		boolean single = inputs.size() == 1;
		long defaults = inputs.stream().filter(input -> Boolean.TRUE.equals(input.getIsDefault())).count();
		if (!single && defaults != 1) {
			violations.add(new InvalidFieldsException.Violation("variants", "DEFAULT_COUNT"));
		}
		if (!violations.isEmpty()) {
			throw new InvalidFieldsException(violations);
		}
		return inputs.stream()
			.map(input -> new NewProduct.Variant(input.getSku(), input.getName(), input.getBasePrice(),
					single || Boolean.TRUE.equals(input.getIsDefault())))
			.toList();
	}

	private CategoryService.Client client() {
		ClientInfo client = ClientInfo.from(this.request);
		return new CategoryService.Client(ActorContext.staff(AuthenticatedSession.require().userId(), null),
				client.ipAddress(), client.userAgent());
	}

	private static ProductDto toDto(ProductView product) {
		return new ProductDto(product.productId(), product.productCode(), product.slug(), product.name(),
				ProductTypeDto.valueOf(product.productType()), ProductStatusDto.valueOf(product.status()),
				product.primaryCategoryId(), product.shortDescription(), product.description(), product.sortOrder(),
				utc(product.archivedAt()), utc(product.createdAt()), utc(product.updatedAt()), product.version(),
				product.variants().stream()
					.map(variant -> new ProductVariantDto(variant.variantId(), variant.sku(), variant.name(),
							variant.basePrice(), variant.isDefault(), VariantStatusDto.valueOf(variant.status()),
							variant.sortOrder()))
					.toList());
	}

	private static ProductSummaryDto toDto(ProductSummary product) {
		return new ProductSummaryDto(product.productId(), product.productCode(), product.slug(), product.name(),
				ProductTypeDto.valueOf(product.productType()), ProductStatusDto.valueOf(product.status()),
				product.primaryCategoryId(), product.fromPrice(), product.variantCount(), utc(product.updatedAt()),
				product.version());
	}

	private static OffsetDateTime utc(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

}
